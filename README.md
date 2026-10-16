# DirtyFrag (CVE-2026-43284)

DirtyFrag is a hardened fork of [diabl0w/DFRoot](https://github.com/diabl0w/DFRoot). It exploits
CVE-2026-43284 (DirtyFrag), a kernel page cache write primitive, to load a kernel module without an
unlocked bootloader and without a custom kernel. The fork targets Samsung phones (OneUI, RKP and KDP
hardening) and keeps upstream's SU-manager agnostic design.

> [!IMPORTANT]
> Root is **ephemeral**. The kernel module, `su` and any loaded modules are gone the moment the
> device reboots. A reboot always returns the phone to a clean, unrooted state - which is also the
> recovery path if a run goes wrong.

## Install

1. Install the DirtyFrag APK from [Releases](https://github.com/mitschud/DirtyFrag/releases/latest).
2. Install an SU manager that ships a supported late-load binary:
   - Samsung - **required**:
     - [diabl0w's KernelSU](https://github.com/diabl0w/KernelSU/releases)
   - Other:
     - [KernelSU](https://github.com/tiann/KernelSU/releases/latest)
     - [KernelSU-Next](https://github.com/KernelSU-Next/KernelSU-Next/releases/latest)
     - [KowSU](https://github.com/KOWX712/KernelSU/releases/latest)
     - [BakaSU](https://github.com/Baka-SU/BakaSU/releases/latest) (formerly ReSukiSU)
   - APatch-based managers that ship `libapd.so`
3. Open the **Script modules** tab. The fresh plan is **Start daemon → Protect read-only partitions → Disable KSU modules → Unload dfroot → Load manager**.
4. Expand **Load manager**, select the installed app, and save. Its optional **Soft reboot after loading** switch is off by default. Add or reorder modules as needed; a manager with soft reboot must remain the last enabled step.

> [!WARNING]
> Since 1.10 (upstream 3.2) DirtyFrag no longer bundles a late-load CLI. It is invoked directly from
> the selected manager's own `libksud.so` or `libapd.so`, in the manager's native library directory.
> A manager that ships neither library cannot give you root. The older advice to install a CI /
> Actions build of the manager no longer applies.

A fresh install of DirtyFrag gets a new Android UID, so the grant in the manager has to be given
again after every reinstall.

<img width="540" height="1170" alt="Screenshot_20261007_224642" src="https://github.com/user-attachments/assets/b12c72e3-05de-44d1-ad9f-251672370d8b" />

## Features

- **Ordered module plans**: providers for daemon startup, SH scripts and manager loading, plus SHELL
  presets for partition read-only protection, KSU-module disable, marker cleanup and `rmmod dfroot`. Soft reboot is an option of the manager provider, rather than a standalone module.
  Enable, expand, configure, reorder or remove individual steps. Disabled steps are skipped;
  the first failed provider stops all remaining steps. After **Start daemon**, the rest of the plan
  is a file the daemon runs as shell (late-load stays native) - the app does not wait for the FIFO.
- **Precondition check**: before anything runs, the plan is validated against the device - is the
  selected manager still installed and does it still ship the expected library, does the daemon
  step grant root to the steps after it, is `/data/adb/ksud` or `/data/adb/apd` executable for a
  soft reboot. Unmet conditions open a dialog naming the offending step and nothing is executed.
- **Daemon only by default**: manager selection, partition protection and KSU module changes are no
  longer prerequisites or implicit startup side effects.
- **Load manager provider**: select an installed manager carrying `libksud.so` or `libapd.so` and an
  optional KMI. The backend is shown next to the manager and is not chosen separately, because
  `ksud` and `apd` belong to the package that ships them. late-load execs that library in place -
  nothing is copied into the app's data directory. Enable **Soft reboot after loading** to request late-load and reboot through the daemon in one handshake. A successful acknowledgement means cleanup finished and reboot was dispatched; it does not report completion of the reboot command.
- **SH provider**: edit multiline commands or import a UTF-8 `.sh` file (up to 32 KiB). Imported
  content is saved privately, so execution does not depend on future access to the source document.
- **Automatic execution**: opt into running the saved plan on app launch or device boot, directly
  on the module page. No Expert mode or separate root-autorun switch. Configuration recreation
  does not repeat app-launch execution; boot execution is scheduled by Android.
- **Failure interlock**: before any module runs, both automatic switches are committed off and an
  in-flight marker is stored. Only successful completion restores the previous choices. A failed,
  cancelled or interrupted run leaves them off and records the reason. A script which reboots or
  terminates the process before completion therefore cannot automatically relaunch the plan.
  Plans containing an enabled manager with **Soft reboot after loading** keep both automatic switches off even if
  the reboot request returns success; re-enable them manually after checking the result.
- **Migration**: old app/boot scripts and manager choices are imported as disabled modules; old
  automatic-execution flags are not inherited. The original sequence is available as an explicit
  append preset; configure its manager before running.
- **Home**: daemon-channel status, module-plan progress and log export. A ready daemon does not
  imply that a manager's late-load step has succeeded.
- **Terminal**: selectable ANSI output, retained command history and shortcuts for the run markers,
  `rmmod dfroot` and marker cleanup. Shortcuts fill the input; press Run to execute. Cleanup and
  unloading require root.
- **Diagnostics**: run markers and logs live under `/data/user_de/0/df.root/` - `markers/` for the
  step timeline and `daemon/` for logs, including one timestamped file per run (`run-<trigger>-…log`)
  with the daemon's own step trace folded in. Cleanup also removes legacy `/dev/df*` markers. Daemon cleanup preserves logs and does not run `rmmod`; unloading belongs to the explicit **Unload dfroot** module in the default flow.
- **Kernel settings**: automatic or pinned branch, built-in/vivo/Android/custom vermagic, device
  information, theme choices and update checks. The manager provider has its own optional KMI.
- System, light, dark and Monet themes, English and Chinese, advanced logs and Downloads export.
- Shizuku not needed.

The UI uses Jetpack Compose and [MiuiX](https://github.com/compose-miuix-ui/miuix),
following the official shared example's page scaffolds, collapsible app bars, navigation and
preference groups. Native MiuiX blur is used for the bars, with themed opaque backgrounds
when runtime shaders are unavailable. The app requires Android 13 (`minSdk 33`).

## FAQ

**Q: Log says patching failed and that my device isn't vulnerable**

A: Sorry, there is no fix. Either:
  - your device's kernel is too new
  - your device manufacturer backported the official mitigation
  - [accidental mitigation](https://github.com/V4bel/dirtyfrag/issues/23#issuecomment-4405314290)
    (typically seen on kernel 6.1)

**Q: Log says "SUCCESS" or "ksud exited with error", but I don't have root**

A: Disable **Soft reboot after loading** in the manager module while checking the **Load manager** output and exit code.
A ready daemon alone does not mean the manager was loaded. See
[upstream discussion 69](https://github.com/diabl0w/DFRoot/discussions/69).

**Q: I installed a module and now I can't launch root without crashing**

A: Add **Disable KSU modules** between **Start daemon** and **Load manager**, then run the plan
manually. This creates module-disable markers before the manager loads.

**Q: Module + Autorun and now I am bootlooping**

A: Android usually detects the bootloop and offers Safe Mode; otherwise hold **Volume Down** during
boot. From Safe Mode turn the plan’s boot switch off and enable the **Disable KSU modules** step, or uninstall
DirtyFrag until you stabilise. If you cannot get far enough into the system, `adb uninstall df.root`
from any PC works on user builds without root.

**Q: The progress bar sits at 0 percent**

A: That is the app's log-marker mapping, not the exploit. The run window is 70 seconds - do not
re-run a "slow" run, the daemon may still be working.

**Q: How do I remove root cleanly?**

A: Use the in-app **Remove KSU/KSUD** entry. Never run `ksud uninstall` by hand alongside DirtyFrag:
it tries to restore a boot image backup that this flow never made, and can reboot the device.

## Building

```sh
JAVA_HOME="<Android Studio jbr>" ./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/dirtyfrag.apk`.

Release builds are signed with `app/keystore.jks`, which is gitignored (the signing config expects
alias `dirtyfrag` and `dirtyfrag` for both passwords). Generate your own before building:

```sh
keytool -genkeypair -v -keystore app/keystore.jks -alias dirtyfrag \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass dirtyfrag -keypass dirtyfrag
```

The eight prebuilt kernel modules are committed under `app/src/main/jni/ko/`. Upstream gitignores
them and builds them per-KMI in CI; this fork commits them so a plain clone builds without a DDK
toolchain. They are built by this repo's `Build` workflow (the `LKMs / <kmi>` jobs) from the
current `lkm/dfroot.c`, so they must be rebuilt whenever that file changes: run the workflow,
download the `df-lkm-<kmi>` artifacts and drop them in this directory. To build them yourself
instead, use upstream's `make` / podman DDK flow.

## How it works

The Android kernel decrypts AES-CBC ESP packets directly into the page cache of files open for
`splice()`. By crafting `IV = AES_ECB_DEC(key, current_content) XOR desired_content`, any
16-byte-aligned block in a mapped shared library can be overwritten without write permission and
without copy-on-write.

The exploit uses this primitive to patch shellcode into `libc++.so` in the kernel's page cache. The
next privileged call to the hooked function runs the shellcode, which loads the kernel module via
`insmod`.

### Exploit chain

1. **IpSec transform** - the app allocates a `UdpEncapsulationSocket` plus an SPI and builds an
   AES-CBC/HMAC-SHA256 ESP transform through `IpSecManager`.
2. **splicehelper -> crash_dump64** - splicehelper is spliced into `crash_dump64` using the CBC
   primitive. `crash_dump64` runs in the `crash_dump` SELinux domain, which can open `vendor_file`
   labelled files that `untrusted_app` cannot read - the bridge we need.
3. **dfroot.ko -> vendor_file** - the kernel module is written through that bridge into a
   `vendor_file`-labelled file.
4. **libc++ hook** (fires in init, uid=0, tid=1) - shellcode patched at
   `std::ostream::sentry::sentry()`. It checks uid and tid, creates `/dev/df` as a one-shot mutex so
   it cannot re-enter, then clones a helper that execs `insmod` as `vendor_modprobe`. The kernel
   module starts the daemon itself; the helper does not wait for insmod or exec the daemon.
5. **dfroot.ko init** - sets SELinux permissive, text-patches DEFEX and Oppo handlers to return 0
   instead of leaving kprobes planted, `rmmod`s Oppo security modules if present, then uses
   `call_usermodehelper` (`UMH_WAIT_EXEC`) to drop a `kernel-module` marker under the app's data
   directory, chowned to the app, and `exec` the staged daemon. Init returns `-ENODEV` so the
   module unloads; the text patches remain.
6. **dfdaemon** - opens its FIFO control channel, reads the app's device-protected settings,
   adopts the zygote environment and marks itself ready. If the app staged a module plan under
   `files/runs/plan`, the daemon runs it immediately - shell steps as `/system/bin/sh`, **Load manager**
   as native late-load of `libksud.so`/`libapd.so`. The app watches `plan.status` / `plan.log` rather
   than driving each step over the FIFO. When soft reboot is enabled, cleanup happens **before late-load**;
   `state=accepted` means cleanup finished and reboot will be dispatched, not that late-load or reboot
   completed. The FIFO remains for the terminal. Step markers and logs land in
   `/data/user_de/0/df.root/{markers,daemon}`.

## Credits

- Upstream project and exploit chain: https://github.com/diabl0w/DFRoot
- Original PoC and various code: https://github.com/lsposed/lspromise
- SELinux permissive kernel modules and various code: https://github.com/polygraphene/DFReroot
- Unprivileged XFRM socket method: https://github.com/combeng6th/DirtyInit
