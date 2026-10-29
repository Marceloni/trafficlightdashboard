# Code Review: CoffeeMakerService

| | |
|---|---|
| **Date** | 2026-10-02 |
| **Reviewed revision** | `main` @ `3339c8c` ("implement loadState in WnckCoffeeMakerService") |
| **Scope** | `apis/.../examples/coffeemakerservice`, `components/.../example/coffeemakerservice` (+ `components/helper/CheckingHelper`), `javafx/.../coffeemaker`, `javafx/.../controllers/CoffeeMakerServiceController`, related FXML files and the component tests |
| **Tooling** | Gradle 9.7.1, Java 25, JUnit 6.1.3, JaCoCo, Checkstyle 10.14.2 (`config/checkstyle/checkstyle.xml`), PMD 7.16.0, `javadoc` |

> Note: the `loadState` implementation and its five tests were added in the reviewed revision
> during the same working session as this review. They are reviewed like any other code.

## 1. Summary

The component is a readable, small example of the State pattern behind a clean facade
(`CoffeeMakerService` / `AdminCoffeeMakerService`). The JavaFX module talks to the component
only through the facade, coverage of the components module is above the 80 % target, and
there are no empty catch blocks.

The main weaknesses are:

- **A real concurrency bug**: switching a maker off while it is heating (or cleaning/brewing
  ends asynchronously) is overwritten by the background thread – the maker ends in `READY`
  although it was switched off (verified with a test, see F-1).
- **Thread-safety in general**: state, listener list and cup counter are shared between the
  caller thread and worker threads without synchronisation.
- **Callback tests are weak**: the cup counter callback is never asserted, state callbacks
  are only counted, and one "bad case" test passes for the wrong reason.
- **Javadoc and CSS requirements are not met**: 40 trivial javadoc warnings in components,
  21 in the JavaFX coffee maker classes; no CSS file exists, styling is done in FXML/Java.

### Requirement checklist

| # | Requirement | Result | Details |
|---|---|---|---|
| 1 | Log message with method name and parameters as first line of every facade/callback implementation | ⚠️ **Partly met** | [§3.1](#31-logging-of-facade-and-callback-methods) – `getStateForSave()` and both `CoffeeMakerViewModel` callbacks have no log |
| 2 | ≥ 80 % JaCoCo coverage (components) | ✅ **Met** | Line 81.6 %, instruction 85.2 %, branch 82.1 % – [§3.2](#32-test-coverage-jacoco) |
| 3 | Important aspects tested, especially callback notification | ❌ **Not met** | [§3.3](#33-test-quality-and-callback-notification) |
| 4 | DRY | ⚠️ **Partly met** | [§3.4](#34-dry) |
| 5 | SOLID | ⚠️ **Partly met** | [§3.5](#35-solid) |
| 6 | Max. 5 non-trivial checkstyle problems | ✅ **Met** (production code) | 0 in components, 2 trivial in apis, 6 in javafx coffee maker classes – [§3.6](#36-checkstyle) |
| 7 | JavaFX uses only the facade (implementation class only for instantiation) | ✅ **Met** | [§3.7](#37-facade-usage-in-javafx) |
| 8 | JavaFX design in separate CSS files | ❌ **Not met** | No CSS file at all – [§3.8](#38-css) |
| 9 | Javadoc generation without trivial warnings | ❌ **Not met** | [§3.9](#39-javadoc) |
| 10 | No empty catch clauses | ✅ **Met** | but see remarks in [§3.10](#310-exception-handling) |

## 2. Findings by severity

### 🔴 High

**F-1 – Asynchronous state threads overwrite later state changes (race condition).**
[HeatingState.java:37](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/makerstates/HeatingState.java#L37),
[CleaningState.java:66](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/makerstates/CleaningState.java#L66),
[BrewingState.java:36](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/makerstates/BrewingState.java#L36)

`RunHeating` unconditionally calls `maker.setMakerState(new ReadyState(maker))` after the
sleep. `HeatingState.onSwitchOff()` is allowed and sets `SwitchOffState`, but one second later
the heating thread sets `READY` again. Reproduced with:

```java
maker.switchOn();
maker.switchOff();
Thread.sleep(1500);
assertEquals(State.OFF, maker.getState());   // fails: actual READY
```

The same pattern exists in `CleaningState` and `BrewingState`; it does not show up there today
only because those states forbid every transition. *Fix:* the worker thread should only switch
to `READY` if the maker is still in the state that started it, e.g.
`if (maker.getMakerState() == HeatingState.this) { ... }` inside a synchronised block, or cancel
the thread/`ScheduledFuture` in `onSwitchOff()`.

**F-2 – `WnckCoffeeMaker` is not thread-safe.**
[WnckCoffeeMaker.java:19-20, 56](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/WnckCoffeeMaker.java#L19)

`makerState` is written by worker threads and read by the caller thread without `volatile` or
locking; `listeners` is a plain `ArrayList` that is iterated in worker threads while
`addCallback/removeCallback` may modify it (→ `ConcurrentModificationException`);
`cupsSinceLastService++` is not atomic, and the check-then-act in `brew()` (state check, then
transition) is not atomic either. *Fix:* make the public methods of `WnckCoffeeMaker`
`synchronized` (simple and adequate here), use a `CopyOnWriteArrayList` for listeners and
notify listeners outside the lock.

### 🟠 Medium

**F-3 – Shared static exception instances.**
[MessageHelper.java:8 ff.](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/makerstates/MessageHelper.java#L8)
All states throw the *same* `IllegalStateException` objects created at class-initialisation
time. Their stack traces point to `MessageHelper.<clinit>` instead of the throw site, and the
objects are mutable (`addSuppressed`, `initCause`) and shared between threads. *Fix:* replace
the fields by factory methods (`static IllegalStateException alreadySwitchedOn()`) or by
message constants and `throw new IllegalStateException(MessageHelper.ALREADY_SWITCHED_ON)`.

**F-4 – API contract of `addCoffeeMaker` is violated for duplicate ids.**
[WnckCoffeeMakerService.java:194](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/WnckCoffeeMakerService.java#L194)
A configuration with an explicit id that already exists results in an `IllegalStateException`,
while the interface documents `IllegalParameterException` for invalid configurations. Ids
`< -1` and `0` are accepted silently. *Fix:* throw `IllegalParameterException` and validate the
id range.

**F-5 – View model assumes the maker is `OFF`.**
[CoffeeMakerViewModel.java:57](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/viewmodel/CoffeeMakerViewModel.java#L57)
The initial state and button enablement are hard-coded to `OFF` instead of being read via
`service.getState(id)`. It works today only because nothing else switches makers on. The
`// TODO: get the real number of cups` at line 55 is stale – the value is already taken from the
configuration.

**F-6 – Persistence is fragile.**
[CoffeeMakerServiceController.java](../javafx/src/main/java/de/hhn/it/devtools/javafx/controllers/CoffeeMakerServiceController.java)
State is only written in `shutdown()`; a crash loses all cup counts. A corrupt or incompatible
`coffeemaker.ser` throws a `RuntimeException` from the controller constructor and the tab cannot
be opened. `ServiceState` and `CoffeeMakerConfiguration` declare no `serialVersionUID`, so every
recompilation that changes the classes can invalidate the file. *Fix:* add
`serialVersionUID`, catch load errors (log + fall back to demo data + inform the user), persist
after admin changes.

### 🟡 Low

- **F-7** `CheckingHelper.assertThatIsReadableString` in `addCoffeeMaker`
  ([L175-176](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/WnckCoffeeMakerService.java#L175))
  can never fail – the `CoffeeMakerConfiguration` constructor already rejects null/blank values
  with `NullPointerException`/`IllegalArgumentException`. Either remove it or move validation
  to one place.
- **F-8** `InterruptedException` is caught and logged, but the interrupt flag is not restored
  and the state change runs anyway (see [§3.10](#310-exception-handling)).
- **F-9** `PersistenceManager.path` is never read
  ([L20](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/helper/PersistenceManager.java#L20)).
- **F-10** Logger calls with string concatenation (`MakerState`, `BrewingState`,
  `CoffeeMakerViewModel.setState`) – use `{}` placeholders.
- **F-11** `Recipe` passed to `brewCoffee` is ignored by the component. Acceptable for a
  simulation, but should be mentioned in the javadoc.
- **F-12** Each `CoffeeMakerViewModel` registers itself as listener and is never removed
  (`removeCallback`), which leaks view models if makers are removed at runtime.

## 3. Requirement details

### 3.1 Logging of facade and callback methods

| Class | Method | Log in first line? |
|---|---|---|
| `WnckCoffeeMakerService` | `getCoffeeMakers`, `getCoffeeMaker`, `addCallback`, `removeCallback`, `switchOn`, `switchOff`, `brewCoffee`, `cleanIt`, `getState`, `addCoffeeMaker`, `removeCoffeeMaker`, `loadState` | ✅ |
| `WnckCoffeeMakerService` | `getStateForSave` ([L212](../components/src/main/java/de/hhn/it/devtools/components/example/coffeemakerservice/provider/WnckCoffeeMakerService.java#L212)) | ❌ no log at all |
| `CoffeeMakerViewModel` (listener) | `newState` ([L116](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/viewmodel/CoffeeMakerViewModel.java#L116)), `newCupsCounter` ([L121](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/viewmodel/CoffeeMakerViewModel.java#L121)) | ❌ no log |
| `DummyCallback` (test) | `newState`, `newCupsCounter` | ✅ (but logs `cupsCounter` instead of the method name `newCupsCounter`) |
| `TestCoffeeMaker.SimpleCoffeeMakerListener` (test) | `newState`, `newCupsCounter` | ❌ no log |

Minor: `addCoffeeMaker` logs `descriptor = {}`, while the parameter is called `configuration`.

### 3.2 Test coverage (JaCoCo)

Measured with `./gradlew :components:test :components:jacocoTestReport` (CSV report enabled via
a temporary init script, no change to the build files).

| Scope | Instructions | Branches | Lines |
|---|---|---|---|
| **components (total)** | **85.2 %** | **82.1 %** | **81.6 %** |
| coffee maker packages only | 87.3 % | 95.5 % | 83.3 % |

Weak spots: `ErrorState` 0 % (the state is unreachable – no transition leads into it),
`ReadyState`/`CleaningState`/`SwitchOffState`/`BrewingState` only 3–4 of 6–7 methods,
`WnckCoffeeMaker` 15 of 19 methods.

Recommendation: the 80 % target is met only by a small margin and is not enforced. Add a
`jacocoTestCoverageVerification` rule with `minimum = 0.80` and hook it into `check`.

### 3.3 Test quality and callback notification

What is tested well: admin add/remove/load (good and bad cases), listener registration
errors (null, twice, not registered), heating → ready and cleaning → ready via `getState`.

Gaps:

1. **`newCupsCounter` is never asserted.** `SimpleCoffeeMakerListener` collects `cupCounters`
   ([TestCoffeeMaker.java:114](../components/src/test/java/de/hhn/it/devtools/components/example/coffeemakerservice/junit/TestCoffeeMaker.java#L114)),
   but no test brews coffee and checks the values.
2. **State callbacks are only counted**, e.g. `assertEquals(2, listener.states.size())`
   ([L42](../components/src/test/java/de/hhn/it/devtools/components/example/coffeemakerservice/junit/TestCoffeeMaker.java#L42)).
   Assert the sequence instead: `assertEquals(List.of(HEATING, READY), listener.states)`.
3. **No callback test through the service facade** – `TestCoffeeMakerServiceGoodCases` only
   adds and removes a `DummyCallback`, without triggering any notification.
4. **Test passes for the wrong reason:** `brewCoffee(0, null)`
   ([TestCoffeeMakerServiceBadCases.java:66](../components/src/test/java/de/hhn/it/devtools/components/example/coffeemakerservice/junit/TestCoffeeMakerServiceBadCases.java#L66))
   expects an `IllegalParameterException` for the null recipe, but id `0` does not exist (ids
   start at 1), so the exception is caused by the unknown id. Use an existing id.
5. **Missing scenarios:** brewing → ready and cup counter increment; switch off from `READY`;
   all forbidden transitions in `ReadyState`, `CleaningState`, `BrewingState`, `SwitchOffState`
   (only `HeatingState` has a dedicated test class); switching off during heating (would have
   found F-1); listeners removed while notifications run.
6. **Timing-based tests:** `Thread.sleep(… + DELTA)` makes the suite slow (~1–2 s per test) and
   potentially flaky on loaded CI machines. Listeners collect into a non-synchronised
   `ArrayList` from worker threads. Prefer a `CountDownLatch`/`BlockingQueue` in the listener and
   `await` with timeout, and make the durations injectable.

### 3.4 DRY

- `HeatingState`, `CleaningState`, `BrewingState` contain three nearly identical inner
  `Runnable`s ("sleep N ms, then switch to `ReadyState`"). Extract an abstract
  `TimedMakerState(maker, durationMillis)` – this is also the single place to fix F-1.
- `raiseExceptionToUi` exists twice
  ([CoffeeMakerServiceController.java:153](../javafx/src/main/java/de/hhn/it/devtools/javafx/controllers/CoffeeMakerServiceController.java#L153),
  [CoffeeMakerController.java:81](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/view/CoffeeMakerController.java#L81));
  the copy in `CoffeeMakerServiceController` is unused.
- `CoffeeMakerController.onSwitchOn/onSwitchOff/onCleanIt/onBrew` repeat the same
  try/catch/alert pattern – a small helper taking a lambda removes the four copies.
- `SliderRecipeConfigurator.slider2Quantity` and `SliderLabeler.toString` duplicate the
  thresholds 0.2 / 0.4 / 0.7 ([L69](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/custom/SliderRecipeConfigurator.java#L69),
  [L111](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/custom/SliderRecipeConfigurator.java#L111)).
- `CoffeeMakerServiceController` fills `observableConfigurations` twice in the demo-data path
  (line 92 and again via `clear()/addAll()` at line 72).
- `WnckCoffeeMaker.addCallback/removeCallback` repeat the null check;
  `removeCoffeeMaker` repeats the lookup logic of `getMakerById`.
- Validation of location/model exists in `CoffeeMakerConfiguration` *and* in
  `addCoffeeMaker` (F-7).
- Tests: identical `@BeforeEach` setup in `TestCoffeeMakerServiceGoodCases` and
  `…BadCases`; the `DELTA` constant is defined in two classes.

### 3.5 SOLID

- **S** – `CoffeeMakerServiceController` mixes persistence, demo-data creation, list handling
  and view switching. Move "load or create initial state" into a separate class (e.g. next to
  `PersistenceManager`).
- **O** – adding a state requires changes in `CoffeeMakerViewModel.setState` (switch over all
  states) and new `MessageHelper` constants; acceptable for the size, but a
  `Map<State, ButtonConfig>` would be easier to extend.
- **L** – no violations found. `RecipeConfigurator.getRecipe()` has a side effect (sets the
  property) in the slider implementation, which a caller would not expect from a getter.
- **I** – good: the split into `CoffeeMakerService` (user) and `AdminCoffeeMakerService`
  (admin) is exactly what ISP asks for.
- **D** – `MakerState` depends on the concrete `WnckCoffeeMaker` instead of an abstraction for
  "context that can change its state"; states instantiate their successors directly (normal for
  the State pattern, but it couples all state classes). The JavaFX module declares
  `uses …CoffeeMakerService` in `module-info.java` but instantiates `WnckCoffeeMakerService`
  directly; `ServiceLoader` would remove the compile-time dependency on the component class.

### 3.6 Checkstyle

Configuration `config/checkstyle/checkstyle.xml`, Checkstyle 10.14.2. Only `components`
applies the plugin, with `checkstyleTest` disabled; `apis`, `javafx` and the component tests
were checked via a temporary init script.

| Source set | Findings (coffee maker files) | Non-trivial |
|---|---|---|
| components `main` | 0 | 0 |
| apis `main` | 2 (`Quantity`: comment indentation, missing period) | 0 |
| javafx `main` | 6 (4× missing javadoc in `RecipeConfigurator`/`PersistenceManager`, 2× indentation in `SliderRecipeConfigurator`) | ≤ 4 (missing javadoc) |
| components `test` | 69 (35× import order, 10× missing type javadoc, 5× line length, 4× method name, …) | 4 (method names starting with an upper-case letter, e.g. `CheckSuccessfulRegistrationOfCallback`) |

The requirement is met for production code. The test code is not checked by the build; if it
were, the four method-name violations and the star import in `TestAdminInterfaceGoodCases`
would be worth fixing. Two of the line-length findings (`TestAdminInterfaceGoodCases`
L53/L104) belong to the newly added `loadState` tests.

### 3.7 Facade usage in JavaFX

Met. The only reference to the implementation is the instantiation
`new WnckCoffeeMakerService()` in `CoffeeMakerServiceController`; afterwards the object is used
exclusively as `CoffeeMakerService` / `AdminCoffeeMakerService`. `CoffeeMakerViewModel`
receives a `CoffeeMakerService`. (See §3.5 D for the `ServiceLoader` suggestion.)

### 3.8 CSS

Not met. There is no `.css` file in `javafx/src/main/resources`, and no stylesheet is loaded.
Styling is spread over:

- FXML: `<Font size="…"/>` for labels and the brew button
  (`CoffeeMakerControl.fxml`, `CoffeeMakerService.fxml`), fixed `prefWidth/prefHeight`,
  padding and margins.
- Java: `ingredientLabel.setPrefWidth(70.0)`, `quantitySlider.setPrefWidth(200.0)`
  ([SliderRecipeConfigurator.java:90-92](../javafx/src/main/java/de/hhn/it/devtools/javafx/coffeemaker/custom/SliderRecipeConfigurator.java#L90)).

*Fix:* add `css/coffeemaker.css`, attach it via `stylesheets="@…"` in the FXML root, give the
nodes `styleClass` values and move fonts, sizes and spacing there.

### 3.9 Javadoc

| Module | Result |
|---|---|
| apis (`:apis:javadoc`) | ✅ no warnings |
| components (`:components:myJavadocs`) | ❌ **fails** with 2 errors (`module not found: org.slf4j`, `devtools.apis`) – the custom task has no classpath configured |
| components (with classpath) | ❌ 40 warnings, all "no comment" / "default constructor" – internal `CoffeeMaker` interface (9), `MakerState` (9), `MessageHelper` (10), `WnckCoffeeMaker` (4), state constructors, `module-info` |
| javafx coffee maker classes | ❌ 21 warnings – `CoffeeMakerViewModel` property accessors and actions (14), `PersistenceManager` (4), `RecipeConfigurator` (3) |

In addition, the root `build.gradle` sets `source = project(':apis').sourceSets.main.allJava` for
the `javadoc` task of *every* project, so `:components:javadoc` and `:javafx:javadoc` document
the apis sources instead of their own.

### 3.10 Exception handling

No empty catch blocks were found. Remarks:

- `InterruptedException` in the three timed states is logged but the interrupt flag is not
  restored (`Thread.currentThread().interrupt()`), and the state transition continues anyway.
- `CoffeeMakerServiceController` uses `e.printStackTrace()` (line 95) instead of the logger, and
  two catch blocks with an identical body (`IOException`, `ClassNotFoundException`) could be one
  multi-catch.
- The error dialog shows only `e.getMessage()`; because of F-3 the logged stack traces are
  misleading.

## 4. Positive observations

- Clear separation of API (`apis`), implementation (`components`) and UI (`javafx`), with JPMS
  `provides … with` declarations.
- The State pattern makes the allowed transitions easy to read; illegal transitions are
  rejected consistently with `IllegalStateException`.
- `CoffeeMakerConfiguration` as an immutable record with `withId`/`withCups` is a good choice.
- MVVM in the UI: property bindings in `CoffeeMakerController`, callbacks marshalled to the FX
  thread with `Platform.runLater`.
- Use of a JUnit `ParameterResolver` and `@DisplayName` makes the test reports readable.
- PMD (project ruleset) reports no violations for the components module.

## 5. Recommended next steps

1. Fix F-1 and F-2 (and add a regression test for "switch off while heating").
2. Strengthen the callback tests (§3.3, items 1–4) and replace `Thread.sleep` with latches.
3. Introduce a CSS file for the coffee maker views (§3.8).
4. Fix the `myJavadocs` classpath and add the missing javadoc comments (§3.9).
5. Add the missing logs (§3.1) and the JaCoCo verification rule (§3.2).
6. Address the DRY items, starting with the timed-state base class (§3.4).
