# Releasing

Lull ships to both stores from `main`, under one version number:

- **App Store**: [`.github/workflows/app-store.yml`](../.github/workflows/app-store.yml) tests BedJetKit,
  builds and signs the iPhone app with its Apple Watch app inside, uploads it to App Store Connect, and
  submits it for review. Once Apple approves it, it's released automatically.
- **Google Play**: [`.github/workflows/play-store.yml`](../.github/workflows/play-store.yml) runs the
  Android tests, builds and signs the Android app and the Wear OS app, uploads them to Google Play and
  releases them to production. Google reviews them before they reach people.

Each workflow skips pushes that only touch the other platform. Both run for changes to shared files,
such as the version in `Lull.xcodeproj`.

## Cutting a release

1. Raise **Version** in Xcode (`MARKETING_VERSION`, for example 1.0 → 1.1). The Android app reads the
   same value, so this releases both.
2. Write what's new in both [`fastlane/metadata/en-US/release_notes.txt`](../fastlane/metadata/en-US/release_notes.txt)
   and [`fastlane/metadata/android/en-US/changelogs/default.txt`](../fastlane/metadata/android/en-US/changelogs/default.txt).
3. Merge to `main`, then watch **App Store release** and **Google Play release** in the Actions tab.

# App Store

What a push does depends on the version in Xcode (`MARKETING_VERSION`, shown as **Version** on the
Lull target's General tab):

| State of that version on App Store Connect | What the workflow does |
| --- | --- |
| Already live on the App Store | Nothing. Raise the version to release. |
| New, or being prepared | Uploads a build and submits it for review. |
| Waiting for review or in review | Pulls that submission, then submits the newer build. **This puts you back in the review queue.** |

Build numbers are picked automatically: one higher than anything already uploaded for that version.
Docs-only changes (Markdown files and screenshots) don't trigger a release.

## App Store one-time setup

The workflow stays off until the steps below are done.

### 1. App Store Connect API key

In [App Store Connect → Users and Access → Integrations → App Store Connect API](https://appstoreconnect.apple.com/access/integrations/api),
create a **Team Key** with the **App Manager** role. Note the **Key ID** and **Issuer ID**, and download
the `.p8` file. Apple lets you download it only once.

### 2. Apple Distribution certificate

The workflow signs with an Apple Distribution certificate exported as a `.p12` file:

1. In Xcode, open **Settings → Accounts**, select your team, and click **Manage Certificates**.
2. Click **+** and choose **Apple Distribution**. Skip this if one already shows without "(cloud managed)".
3. In **Keychain Access**, find **Apple Distribution: …** under **My Certificates**, right-click it,
   choose **Export**, and save it as `distribution.p12` with a strong password.

### 3. Secrets

The secrets live in the repository's `app-store` environment, which only `main` can use. Run these from
the folder with your files, replacing the placeholders. Each command prompts for or reads the value
locally, so nothing ends up in your shell history:

```bash
gh secret set ASC_KEY_ID --env app-store --repo rkanjani/lull
```

```bash
gh secret set ASC_ISSUER_ID --env app-store --repo rkanjani/lull
```

```bash
gh secret set ASC_KEY_P8 --env app-store --repo rkanjani/lull < AuthKey_XXXXXXXXXX.p8
```

```bash
base64 -i distribution.p12 | gh secret set DIST_CERT_P12_BASE64 --env app-store --repo rkanjani/lull
```

```bash
gh secret set DIST_CERT_PASSWORD --env app-store --repo rkanjani/lull
```

Then delete the local `.p12` and keep the `.p8` somewhere safe, such as a password manager.

### 4. Widgets, the watch app: App Group and bundle identifiers

The widgets ship as an extension inside the app (`com.rohankanjani.lull.widgets`). The Apple Watch app
(`com.rohankanjani.lull.watchkitapp`) ships inside the app too, with its watch face complications inside it
(`com.rohankanjani.lull.watchkitapp.widgets`). Each reads what's saved through the App Group
`group.com.rohankanjani.lull`. The workflow fetches a distribution profile for each, which only includes the
App Group once it is registered and assigned to every identifier. Do this once, before the first release with
widgets, and again before the first with the watch app:

1. Open `Lull.xcodeproj`, choose a connected iPhone (with a paired Apple Watch) as the destination, and
   build. With automatic signing, Xcode registers the identifiers and the App Group, and turns on App Groups
   for each.
2. In [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/identifiers/list),
   check that `com.rohankanjani.lull`, `com.rohankanjani.lull.widgets`, `com.rohankanjani.lull.watchkitapp`
   and `com.rohankanjani.lull.watchkitapp.widgets` all list `group.com.rohankanjani.lull` under App Groups.

The release lane signs all four targets and gives them the app's version, so only the app's **Version**
needs raising. The watch app has no listing of its own: App Store Connect shows it under the iPhone app.

Before the first release with the watch app, add Apple Watch screenshots to the version in App Store
Connect, under its **Apple Watch** tab. The lane doesn't upload screenshots, and App Store Connect won't
submit a version with a watch app until it has them. Until then the build uploads but the submission
fails; run the workflow again once they're added.

### 5. Turn it on

```bash
gh variable set APP_STORE_RELEASE --body true --repo rkanjani/lull
```

To run it right away without a new commit, use **Actions → App Store release → Run workflow**, or:

```bash
gh workflow run app-store.yml --repo rkanjani/lull
```

To pause releases, set the variable to `false`.


# Google Play

The Play lane checks the production tracks first, the phone's and the Wear OS one:

| State of that version on Google Play | What the workflow does |
| --- | --- |
| Already on both production tracks (live or in review) | Nothing. Raise the version to release. |
| On one of them only | Uploads the other app's build and releases it to production. |
| New | Uploads a build, updates the listing text and release notes, and releases it to production. |

The Wear OS app is a second bundle of the same app, released on the Wear OS track (`wear:production`)
alongside the phone app. Each track is checked on its own, so running the workflow again finishes a
release where only one of the two apps went through. Version codes are picked automatically: one higher
than anything already uploaded to any track, with the watch's one above the phone's. The
listing images in `fastlane/metadata/android/en-US/images` are uploaded by hand when they change;
[`android/scripts/play-listing-images.sh`](../android/scripts/play-listing-images.sh) rebuilds them from
fresh `StoreScreenshots` captures.

## Google Play one-time setup

The workflow stays off until the steps below are done.

### 1. Developer account and app

In [Play Console](https://play.google.com/console), create the app **Lull for BedJet** (package
`com.rohankanjani.lull`, free, app), then complete everything under **Policy and programs → App content**
(privacy policy: `https://github.com/rkanjani/lull/blob/main/PRIVACY.md`; no ads; data safety: no data
collected or shared; target audience 18+; content rating questionnaire) and the store listing.

### 2. Upload key

Release builds are signed with an upload key, and Google re-signs them with the app signing key it keeps
(Play App Signing). Create the upload key once and keep it, and its password, in a password manager:

```bash
keytool -genkeypair -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

If it's ever lost, request an upload key reset in **Test and release → App integrity**.

### 3. Service account

The workflow publishes as a Google Cloud service account:

1. In [Google Cloud](https://console.cloud.google.com), pick or create a project, enable the **Google
   Play Android Developer API**, create a service account and download a JSON key for it.
2. In Play Console, **Users and permissions → Invite new users**, add the service account's email, and
   give it **Release apps to testing tracks** and **Release to production, exclude devices, and use
   Play App Signing** for Lull, plus **Manage store presence** for the listing text.

### 4. Secrets

The secrets live in the repository's `play-store` environment, which only `main` can use:

```bash
gh secret set PLAY_SERVICE_ACCOUNT_JSON --env play-store --repo rkanjani/lull < play-service-account.json
```

```bash
base64 -i upload.jks | gh secret set PLAY_UPLOAD_KEYSTORE_BASE64 --env play-store --repo rkanjani/lull
```

```bash
gh secret set PLAY_UPLOAD_KEYSTORE_PASSWORD --env play-store --repo rkanjani/lull
```

Then delete the local JSON key.

### 5. First release

Google only accepts draft releases for an app that has never been sent for review. Upload the first one
as a draft, then send it for review from Play Console (**Publishing overview → Send for review**):

```bash
gh variable set PLAY_RELEASE_STATUS --body draft --repo rkanjani/lull
```

```bash
gh variable set PLAY_STORE_RELEASE --body true --repo rkanjani/lull
```

```bash
gh workflow run play-store.yml --repo rkanjani/lull
```

Before the first release with the Wear OS app, add it in Play Console: **Test and release → Advanced
settings → Form factors → Add form factor → Wear OS**, opt in to Wear OS distribution, and add Wear OS
screenshots to the listing. Until then the upload to the Wear OS track fails, after the phone app's has gone
through; run the workflow again once Wear OS is added, and it uploads only the watch app.

After the first release is approved, switch to full releases:

```bash
gh variable delete PLAY_RELEASE_STATUS --repo rkanjani/lull
```

To pause Play releases, set `PLAY_STORE_RELEASE` to `false`.

# Security notes

- Secrets are scoped to the `app-store` and `play-store` environments, and only the `main` branch can
  deploy to them, so workflows from forks and pull requests never see them.
- The App Store Connect key can manage apps but not users or payments. Revoke it in App Store Connect
  if it leaks. The Play service account has only the release permissions above; delete its key in
  Google Cloud if it leaks.
- Third-party actions are pinned to commit SHAs. Review the changes before bumping them.
