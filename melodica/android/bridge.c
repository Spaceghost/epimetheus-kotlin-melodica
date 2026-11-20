#include <jni.h>
#include <stdint.h>
#include <stddef.h>
extern int melodica_wire_blocks(uint8_t *, size_t, const uint8_t *, size_t, int);

JNIEXPORT jboolean JNICALL Java_tk_hacker1024_epimetheus_melodica_Wire_transform(
        JNIEnv *env, jobject self, jbyteArray data, jbyteArray key, jboolean decrypt) {
    (void)self;
    jsize size = (*env)->GetArrayLength(env, data), keylen = (*env)->GetArrayLength(env, key);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return JNI_FALSE;
    jbyte *k = (*env)->GetByteArrayElements(env, key, NULL);
    int ok = k && melodica_wire_blocks((uint8_t *)bytes, size, (uint8_t *)k, keylen, decrypt);
    if (k) (*env)->ReleaseByteArrayElements(env, key, k, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, data, bytes, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
