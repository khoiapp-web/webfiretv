# MaxiHop for Fire TV

A small Fire TV app that opens https://maxihop.com full screen and lets it use your USB webcam.

## Remote controls
| Button | Action |
|---|---|
| D-pad | Move the pointer (hold to go faster; push past an edge to scroll) |
| OK / Select | Click where the pointer is |
| Back | Previous page, or exit the app |
| Menu (≡) | Reload the page |

The pointer hides after 4 seconds so it doesn't cover the game. Press any D-pad button to bring it back.

## 1. Build the APK on GitHub (free, nothing to install)
1. Create a new **public** repository on github.com (for example `maxihop-firetv`).
2. Choose **Add file → Upload files** and drag in everything from this folder, **including the hidden `.github` folder**.
   (On a Mac, press Cmd+Shift+. in Finder to show hidden folders.) Commit to `main`.
3. Open the **Actions** tab. The "Build Fire TV APK" job runs in about 3–5 minutes.
4. When it turns green, the APK is at:
   `https://github.com/<your-username>/maxihop-firetv/releases/latest/download/maxihop-firetv.apk`

## 2. Sideload to Fire TV
1. Fire TV: **Settings → My Fire TV → Developer options → Install unknown apps → Downloader → On**.
   (If Developer options is hidden: Settings → My Fire TV → About → click the device name 7 times.)
2. Open **Downloader**, enter the release link above (or a short link to it), and install.
3. Open **MaxiHop** from Your Apps. Choose **Allow** when it asks for the camera and microphone.

Alternative: from a computer on the same Wi-Fi, run `adb connect <fire-tv-ip>` then `adb install -r maxihop-firetv.apk`.

## Troubleshooting
- **Camera denied:** Settings → Applications → Manage Installed Applications → MaxiHop → Permissions → turn on Camera.
- **Black video:** close Zoom and any other app using the webcam, then unplug the webcam and plug it back in.
- **Different site:** change `START_URL` and `TRUSTED_HOSTS` in `MainActivity.java`. Only the trusted sites can use the camera.

## Build on a computer instead
Open this folder in Android Studio and choose **Build → Build APK(s)**.
