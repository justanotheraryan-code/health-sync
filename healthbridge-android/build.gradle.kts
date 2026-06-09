// Top-level build file — shared configuration for all sub-projects/modules.
// Plugins are declared here with `apply false` so versions resolve once and
// the `:app` module applies them locally.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
