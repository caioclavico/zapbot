# Chromium seccomp profile

`chromium-seccomp.json` is based on the Docker/Moby default seccomp profile used for the native AMD64 probe. Its default action remains `SCMP_ACT_ERRNO`; the existing filtered syscall rules are retained.

The only added rule is `clone` when argument 0 contains `CLONE_NEWPID` (`0x20000000`), expressed as `SCMP_CMP_MASKED_EQ`. This is narrower than Playwright's published example, which adds unconditional `clone`, `setns`, and `unshare`. The additional rule was isolated experimentally: removing the unrestricted namespace-syscall rule made Chrome fail to start; adding only this filtered `clone` rule made Chromium 157 start and report both PID and network namespaces plus Seccomp-BPF enabled. The other namespace syscalls remain governed by the base profile's existing rules.

The native probe used Docker Engine 29.9.1 on Linux AMD64 with UID/GID 1000, `cap_drop: ALL`, `no-new-privileges`, no network, a read-only root filesystem, and disposable profile/data volumes. It did not use `SYS_ADMIN`, `--privileged`, `seccomp=unconfined`, the Docker socket, or any A6 production volume.

## Runtime requirements

- Run the Node process as UID/GID 1000 (`node` in the pinned Node base image).
- Apply this profile to the Node container; do not rely on the profile being inside the image, since Docker loads seccomp profiles from the host at container creation.
- Keep `cap_drop: [ALL]` and `no-new-privileges:true`.
- Do not pass Chromium `--no-sandbox`, `--disable-setuid-sandbox`, or `--disable-seccomp-filter-sandbox` flags.
- Existing bind-mounted WhatsApp profiles must be writable by UID 1000 before a future cutover. This change does not chown or inspect any live session; a deployment must preflight ownership and abort on mismatch.

The profile is not a host policy change. It is scoped to containers that explicitly reference this file. Any future Docker Engine or Chromium upgrade requires rerunning the AMD64-native sandbox proof.

## References

- Chromium documents the separate namespace and Seccomp-BPF layers and identifies `chrome://sandbox` as the process-level check: <https://chromium.googlesource.com/chromium/src/%2Bshow/9a2c4b7730a398293bdda2af079a76686ab06038/docs/linux/sandboxing.md>.
- Playwright documents non-root Chromium and a Docker-default-derived seccomp profile for namespace creation: <https://playwright.dev/docs/docker>.
- Playwright's profile source: <https://github.com/microsoft/playwright/blob/main/utils/docker/seccomp_profile.json>.
