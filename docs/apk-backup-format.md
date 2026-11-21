# ARABIFY APK backup format

ARABIFY backups are ZIP archives with a `manifest.json` entry and the original APK files.

- `base.apk` is the package's base APK.
- `splits/split-NN.apk` stores every split APK in the order returned by PackageManager.
- The manifest records the package name, app label, version name, exact byte size, and SHA-256 digest for each APK.
- The app verifies each extracted file against the manifest before opening a PackageInstaller session.
- Restores are submitted as one Android PackageInstaller session so base and split APKs are staged together. Android may require user confirmation.
- APKs must be from the same original install set (matching package/version/signing certificate). A differently signed installed copy may need to be removed manually before the original backup can be installed; ARABIFY never removes an app automatically.
- This backup contains installation files only. It does not copy private app data, save games, accounts, or OBB / external game data.

The archive reader enforces file-count, per-APK, total-size, and manifest-size limits and rejects unsupported schema versions, unsafe entry names, size mismatches, and SHA-256 mismatches. Before `ZipFile` opens the archive, a small end-of-central-directory preflight rejects multi-disk and ZIP64 layouts, more than 65 entries, a central directory larger than 256 KiB, invalid directory bounds, and archives larger than 3 GiB + 16 MiB. This keeps attacker-controlled ZIP metadata from causing unbounded directory allocation. The temporary extracted restore workspace is removed after PackageInstaller has staged the APK bytes, including when staging fails.
