# syscalllogger

Two parts in one repo:

- **syscall_logger/** - KernelPatch module (KPM). Traces su-related syscalls of a target and prints them to the kernel log with the tag `[SCL]`.
- **app/** - SCL Viewer, an Android app. Pick an app from the list: the app sets the module target to that app's uid and shows the module's log live. Both steps use root (su): the module is controlled through a root-only channel (no SuperKey needed, which new APatch no longer has) and the log is read from `dmesg`.

CI: `.github/workflows/build.yml` builds the KPM (needs the `KernelPatch` submodule) and the release APK in two jobs.

## First use

1. Load `syscall_logger_<version>.kpm` in the APatch app (KPM tab).
2. Grant root to SCL Viewer in APatch.
3. Tap an app. The log opens with only new events from that app and everything it spawns, including its root shell.
4. If status says the module "needs Legacy mode" (older APatch/KernelPatch that still require a SuperKey instead of the root-only channel): open Settings, turn Legacy mode on, paste the SuperKey.

## Log screen

- Filter chips (su, execve, connect, setuid, ptrace, mount, stdin, write, unlink, mkdir, rename, socket, bind, dns, http, tls, fork, exit): tap to multi-select; lines matching any selected chip are shown. Combine with the text box (AND) for e.g. "su" chip + "com.example" text.
- Log writes switch: off by default (module command `wr`). On, write() payloads that look like text are logged too (e.g. commands pushed over a pipe fd). Noisy - leave off unless needed.
- Auto timeout switch: on by default. While on, the app pings the module every 5s and the module auto-clears its target after 20s of silence - so it goes quiet by itself if the app is killed or crashes. Turn it off to keep tracking the target indefinitely regardless of whether the app is running (sends `hb 0`).

## Module commands

Sent by the viewer, or by root with `readlink '/.scl:<token> <cmd>'` (answer in dmesg as `[SCL]ctl <token> ...`), or typed in the APatch app (KPM tab, module Control), or passed as load argument:

`target <uid>` (clear, then track every process of that uid) | `clear` (drop all pids and the uid) | `add <pid>` | `del <pid>` | `uid <n>` | `prune` | `verbose <0|1>` | `file <0|1>` | `list`

A bare number means pid. Children of tracked pids are tracked automatically; dead pids are pruned.

## Scope

This covers what a syscall hook can see: su/exec activity, plain file create/delete/rename, sockets, and network traffic up to TLS's SNI. It does NOT see permission-gated framework APIs - camera, mic, location, clipboard, contacts, SMS - since those go through Binder IPC to system_server rather than a plain syscall.
