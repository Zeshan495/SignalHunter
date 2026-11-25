# Signal Hunter (Android)

A native Kotlin/Jetpack Compose utility for comparing cellular reception across active SIM subscriptions, viewing Android-reported serving/neighbor cell signal details, recording recent readings in memory, mapping GPS-tagged signal spots, opening the strongest saved spot in a map app, exporting readings as CSV, and running a small HTTPS download test.

## Important limitations
- This app measures and helps you compare reception; it cannot increase antenna power, amplify a tower, or guarantee faster internet.
- Android/carrier policy controls access to cell information and network-mode changes. The app opens system network settings rather than silently changing preferred network modes.
- Cell information may be throttled, incomplete, hidden by the modem/carrier, or unavailable until location services are enabled.
- Signal map readings are kept in memory while the app process is alive; export the CSV to keep them. Location coordinates stay on-device unless you explicitly share the exported CSV. The app does not upload location or cellular data.
- The speed test downloads about 1 MB from Cloudflare and measures approximate throughput; it is not a full independent speed-test suite.

## Build with GitHub Actions
1. Create a new GitHub repository.
2. Upload the contents of this folder to the repository's `main` branch.
3. Open **Actions** and run **Build Signal Hunter APK**, or push a commit.
4. Download the `signal-hunter-debug-apk` artifact from the successful workflow run.
5. Extract `app-debug.apk` and install it on your Pixel 9 Pro XL. You may need to allow installs from your file manager/browser.

## Village signal mapping\n1. Enable Android Location and grant precise location permission.\n2. Refresh the signal reading at a test location.\n3. Tap **Save GPS spot**. The app records the best-reported active SIM signal and last known device location.\n4. Repeat at several spots. Tap **Open best spot** to open the strongest saved reading in a map app, or export the CSV to compare locations and carriers.\n\nNote: this uses Android's last-known GPS/network location, so wait for a fresh location fix outdoors for better accuracy. It does not automatically discover or triangulate tower coordinates.\n\n## Local build
Requires JDK 17 and Gradle 8.9:
```bash
gradle assembleDebug
```

## Permissions
- Phone: read subscription and cellular information.
- Precise location: Android requires this for many cell-information APIs.
- Internet: run the optional download test.

Package: `com.signalhunter`

## Version 1.2 UI/UX improvements
- Premium dark dashboard with Overview, Signal Map, and Tools navigation.
- Prominent signal quality card, per-SIM comparison cards, and focused diagnostics actions.
- Saved GPS readings persist locally between app launches using Android SharedPreferences.
- Branded adaptive launcher icon.
- No account, analytics, or automatic location upload is included.
