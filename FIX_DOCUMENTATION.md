# Fix Applied: ClassNotFoundException for kotlinx.datetime.Instant

## Problem
The application was failing at runtime with:
```
java.lang.ClassNotFoundException: kotlinx.datetime.Instant
```

This occurred when trying to deserialize API responses that contain `Instant` fields (like `cooldown_expiration`).

## Root Cause
The client module was using `implementation` for its dependencies, which means:
- Dependencies are only available to the client module itself
- They are NOT transitively exposed to modules that depend on the client
- The app module couldn't access `kotlinx-datetime` classes at runtime, even though they're needed for JSON deserialization

## Solution
Changed the client module's `build.gradle.kts` to use `api` instead of `implementation` for dependencies that are part of the public API:

**Changed dependencies:**
- `kotlinx-serialization-json` → `api` (needed for model serialization)
- `kotlinx-coroutines-core` → `api` (all service methods are suspend functions)
- `kotlinx-datetime` → `api` (used in model fields like `Instant`)
- `ktor-client-core` → `api` (base types exposed in exceptions)

**Why use `api`?**
- When a library exposes types from a dependency in its public API, use `api`
- This ensures consumers of the library have access to those types
- Prevents `ClassNotFoundException` at runtime

## Files Modified

### client/build.gradle.kts
```kotlin
dependencies {
    // HTTP client
    api("io.ktor:ktor-client-core:2.3.7")  // Changed to api
    // ... other ktor deps stay as implementation
    
    // Serialization - expose to consumers for model serialization
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")  // Changed to api
    
    // Coroutines - expose to consumers
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")  // Changed to api
    
    // DateTime - expose to consumers for model deserialization
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")  // Changed to api
    
    // Logging stays as implementation (internal only)
    // ...
}
```

### app/build.gradle.kts
Cleaned up redundant dependencies (now provided by client module):
```kotlin
dependencies {
    implementation(project(":utils"))
    implementation(project(":client"))  // Now includes coroutines, datetime, serialization
}
```

## Result
The app module now has access to all necessary runtime dependencies through the client module's `api` configuration. JSON deserialization of `Instant` and other types will work correctly.

## Best Practice
When creating a library module:
- Use `api` for dependencies whose types appear in your public API
- Use `implementation` for internal dependencies
- This follows the principle of least privilege while ensuring consumers have what they need

