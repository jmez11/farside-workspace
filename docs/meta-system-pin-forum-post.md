# Quest system passcode: BiometricPrompt reports available but is allowlist-blocked

Hi Meta developer team,

I'd like to let users authorize access to saved remote-login credentials and SSH private keys with their existing **Quest headset passcode**. Today the app has its own PIN access gate and encrypts saved secrets with Android Keystore. Using a trusted system prompt would remove the need for a second PIN and let the OS handle verification and attempt limits. I only need an authenticated result; the app should never receive the headset passcode.

I implemented a test using the public Android device-credential authentication API. Availability checks suggest the feature is available, but actual requests are rejected by a Horizon OS allowlist. Could someone clarify the supported integration path and whether third-party developers can obtain access?

## Test environment

- Quest 3 with a headset passcode already configured in Quest security settings.
- Horizon OS build incremental: `52641960021500520`.
- Home / `com.oculus.vrshell` version: `209.0.0.321.433`.
- Android 14 / API 34; this headset is on PTC. I haven't compared a stable-channel device.
- App: `com.farside.workspace`, arm64, compile/target SDK 35, minimum SDK 32.
- Sideloaded debug APK, running under its ordinary application UID.
- Manifest declares `android.permission.USE_BIOMETRIC`.
- No device-owner role, privileged permissions, root access or shell identity is used for the app's authentication requests. ADB is used to install the APK, run instrumentation and collect logs.
- Testing performed October 11, 2026.

## API behavior

The public availability checks return:

```text
BiometricManager.canAuthenticate(DEVICE_CREDENTIAL) = 0 (BIOMETRIC_SUCCESS)
KeyguardManager.isDeviceSecure() = false
```

The headset passcode is configured, so the difference between these results is itself something I'd like to understand. Does Horizon's passcode use a separate credential service from Android Keyguard, and which public API should an app use to check it?

The authentication request uses the framework `BiometricPrompt`, with `DEVICE_CREDENTIAL` as the only allowed authenticator. A reduced version of the request is:

```kotlin
// Called from an Activity.
val cancellation = CancellationSignal()

BiometricPrompt.Builder(this)
    .setTitle("Unlock connection")
    .setDescription("Confirm your headset passcode to continue.")
    .setAllowedAuthenticators(
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    .build()
    .authenticate(
        cancellation,
        mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                // Authorize the protected action only after verified success.
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence?
            ) {
                // Keep the protected action locked and surface the failure.
            }
        }
    )
```

The app also cancels requests when their UI is disposed and applies a timeout if the OS does not resolve the request.

## Actual result and logs

The request reaches Horizon's biometric-prompt handler, but the system does not present a usable passcode prompt. The app observes an authentication failure; an activation test displayed `Error authenticating`.

Relevant device-log excerpts, with timestamps and process IDs omitted:

```text
BPVrStatusBar: showAuthenticationDialog called by opPackageName: com.farside.workspace, appName: Farside Workspace
VrLockscreenGatingUtil: BiometricPrompt inmarket pref value: false
VrLockscreenGatingUtil: BiometricPrompt inmarket GK/DC: false
VrLockscreenGatingUtil: BiometricPrompt inmarket denied for com.farside.workspace: package not in allowlist
BPVrStatusBar: Biometric Prompt disabled by GK/DC, dismissing BP request.
BiometricPrompt: Unknown error, modality: 1 error: 0 vendorCode: 0
```

My interpretation is that the public API is present, but an additional Horizon rollout/approval gate prevents this package from using it. I haven't established whether this restriction is specific to PTC, sideloaded apps, this OS build, or a broader developer-access policy.

The app currently retains its App PIN and keeps the system-passcode switch hidden. A future switch would require actual successful system authentication before changing protection settings. Failed or canceled authentication never starts a protected remote session. The existing credential encryption remains in place; I haven't migrated secrets to authentication-bound keys without verifying platform support.

## Questions for Meta

1. **Supported API:** Is framework `BiometricPrompt` with `DEVICE_CREDENTIAL` the supported way for a third-party native Android/Spatial Windows app to request the user's Quest headset passcode? If another public Horizon API is required, could you point me to documentation or a sample?

2. **Allowlist/access:** What controls the `BiometricPrompt inmarket` package allowlist? Is there an application process, developer-program requirement, or release milestone for obtaining access? Can developers test it in sideloaded builds, or does access depend on store distribution, package registration or signing identity?

3. **Capability detection:** Why does `canAuthenticate(DEVICE_CREDENTIAL)` return `BIOMETRIC_SUCCESS` when the actual request is rejected for this package? Is there a supported app-specific capability check, or a documented error that distinguishes rollout/allowlist rejection from an unconfigured passcode or user cancellation?

4. **Keyguard and Keystore:** How should we interpret `isDeviceSecure() == false` with a configured Horizon passcode? Does successful Quest passcode authentication generate authorization usable by Android Keystore keys created with `setUserAuthenticationRequired(true)` and `AUTH_DEVICE_CREDENTIAL`? Are there known limitations for authentication-bound AES-GCM keys on Quest?

5. **Five-minute authorization:** Is there an OS-supported way to reuse successful authentication for approximately five minutes? On Android, `setUserAuthenticationParameters(300, AUTH_DEVICE_CREDENTIAL)` offers a key authorization window. Is that supported with the Quest passcode, and would a recent headset unlock also satisfy it? Alternatively, is an app-managed, in-memory authorization deadline the recommended pattern? We would cache authorization only, never the passcode.

6. **Removal/reset and lifecycle:** What should an app expect when the headset passcode is changed, removed or reset? Are authentication-bound keys invalidated, and is there a public notification or reliable state check for revoking an app's remembered authorization? What is the recommended behavior across sleep, headset removal and profile switching?

7. **Security policy:** Can an ordinary consumer app require a configured system passcode while its protected credentials are enabled, or does enforcing the continued presence of a passcode require managed-device policies? My intended consumer-app behavior is to keep protected access locked when system authentication becomes unavailable and provide a clear recovery path. Any platform guidance here would help.

The immediate question is how to obtain a working system-passcode prompt for this package. The Keystore and lifecycle questions would help us design the feature correctly once access is available. I can provide a minimal reproducible APK and additional sanitized logs if useful.

Android references for the APIs being evaluated:

- [BiometricManager.canAuthenticate](https://developer.android.com/reference/android/hardware/biometrics/BiometricManager#canAuthenticate(int))
- [BiometricPrompt.Builder.setAllowedAuthenticators](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder#setAllowedAuthenticators(int))
- [KeyGenParameterSpec.Builder.setUserAuthenticationParameters](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setUserAuthenticationParameters(int,int))

Thanks for any guidance on supported access, rollout status and the recommended security model for native Quest productivity apps.
