/*
 * Legacy-mode KernelPatch supercall client (same wire format as APatch's
 * app/src/main/cpp/supercall.h: sc_kpm_control()), for APatch/KernelPatch
 * builds that still authenticate with a SuperKey instead of verifying the
 * manager app's signature. Only used when the app's "Legacy mode" switch is
 * on; the default control path is the root-only readlinkat channel in
 * ModuleCtl.kt, which needs no key at all.
 *
 *   syscall(45, key, ver_and_cmd(SUPERCALL_KPM_CONTROL), name, ctl_args, out_msg, outlen)
 *
 * The kernel only uses (cmd & 0xFFFF) and authenticates with the SuperKey;
 * the version part of the command is ignored (KernelPatch >= 0.10.5).
 */
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>

#define SCL_NR_SUPERCALL   45
#define SCL_SC_KPM_CONTROL 0x1022

static long ver_and_cmd(long cmd)
{
    unsigned long version = (0UL << 16) + (12UL << 8) + 6UL;
    return (long)((version << 32) | (0x1158UL << 16) | ((unsigned long)cmd & 0xFFFF));
}

JNIEXPORT jlong JNICALL
Java_app_sclviewer_Supercall_nativeControl(JNIEnv *env, jobject thiz, jstring jkey, jstring jname,
                                           jstring jargs, jbyteArray jout)
{
    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *args = (*env)->GetStringUTFChars(env, jargs, NULL);
    jsize outlen = (*env)->GetArrayLength(env, jout);
    jbyte *out = (*env)->GetByteArrayElements(env, jout, NULL);
    long rc = -ENOMEM;

    if (key && name && args && out) {
        memset(out, 0, (size_t)outlen);
        errno = 0;
        rc = syscall(SCL_NR_SUPERCALL, key, ver_and_cmd(SCL_SC_KPM_CONTROL), name, args,
                     (char *)out, (long)outlen);
        if (rc == -1 && errno)
            rc = -errno;
    }

    if (out)
        (*env)->ReleaseByteArrayElements(env, jout, out, 0);
    if (key)
        (*env)->ReleaseStringUTFChars(env, jkey, key);
    if (name)
        (*env)->ReleaseStringUTFChars(env, jname, name);
    if (args)
        (*env)->ReleaseStringUTFChars(env, jargs, args);
    return (jlong)rc;
}
