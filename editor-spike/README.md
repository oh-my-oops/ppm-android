# Editor evaluation harness

Opt-in application, not a production editor dependency. See
[the evidence and open gates](../../docs/android-editor-spike.md).

Baseline uses published Aardink 0.6.2. A local AAR can be substituted to evaluate the checked-in
IME patch. No Maven publishing or credentials are needed.

```sh
git clone --depth 1 --branch v0.6.2 https://github.com/aardarch/aardink.git /tmp/ppm-aardink-062
# Verify peeled HEAD: 3fbaef67bdc41170727e6e83e3485591b7ed6100
cd /tmp/ppm-aardink-062
git apply /path/to/ppm-android/android/editor-spike/patches/aardink-0.6.2-ime-bounds.patch
git apply /path/to/ppm-android/android/editor-spike/patches/aardink-0.6.2-code-input.patch
JAVA_HOME=/opt/android-studio/jbr ANDROID_HOME=/home/thawngho/Android/Sdk ./gradlew :editor:assembleAndroidMain :sample:testDebugUnitTest --tests com.aardarch.aardink.sample.EditorInputConnectionTest
cd /path/to/ppm-android
JAVA_HOME=/opt/android-studio/jbr ./android/gradlew -p android -PeditorSpike=true -PaardinkSpikeAar=/tmp/ppm-aardink-062/editor/build/outputs/aar/editor.aar :editor-spike:assembleRelease
```

Paths above are examples for this workspace; set SDK/JDK paths for another machine. Omit
`aardinkSpikeAar` for the unmodified published baseline. A local build displays `Aardink 0.6.2 local`.
The first patch bounds Android IME reads without splitting surrogate pairs. The second asks the
keyboard to disable spelling suggestions/autocorrection for code. Both include regression tests; it does not adopt
Aardink as PPM's editor or change its license/public API. Upstream is Apache-2.0; source headers
are retained. Do not apply this v0.6.2 patch to a different upstream revision without review.

Install only on the designated PPM emulator. The synthetic fixture never opens a real project.
Snapshot compares every UTF-16 code unit against the original fixture on explicit request, and
logs only bounded text excerpts; keep its verification work out of timing samples by resetting
metrics before each measured gesture sequence.
