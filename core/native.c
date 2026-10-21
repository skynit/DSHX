#include <node_api.h>
#include <sys/file.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>

/* Android has flock(2); keep the upstream kernel-lock contract, including EAGAIN. */
static napi_value fail(napi_env env, const char *operation) {
    int code = errno;
    napi_value message, error, name;
    napi_create_string_utf8(env, operation, NAPI_AUTO_LENGTH, &message);
    napi_create_error(env, NULL, message, &error);
    const char *label = code == EAGAIN ? "EAGAIN" : code == EEXIST ? "EEXIST" :
        code == ENOENT ? "ENOENT" : code == EACCES ? "EACCES" : "EIO";
    napi_create_string_utf8(env, label, NAPI_AUTO_LENGTH, &name);
    napi_set_named_property(env, error, "code", name);
    napi_throw(env, error);
    return NULL;
}

static napi_value lock_file(napi_env env, napi_callback_info info) {
    size_t count = 1;
    napi_value args[1], result;
    int fd;
    napi_get_cb_info(env, info, &count, args, NULL, NULL);
    if (count != 1 || napi_get_value_int32(env, args[0], &fd) != napi_ok || fd < 0) {
        napi_throw_type_error(env, NULL, "Expected a file descriptor");
        return NULL;
    }
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) return fail(env, "flock failed");
    napi_get_undefined(env, &result);
    return result;
}

/* Atomic no-clobber publication for Android filesystems that reject hard links. */
static napi_value publish(napi_env env, napi_callback_info info) {
    size_t count = 2, length;
    napi_value args[2], result;
    char from[4096], to[4096];
    napi_get_cb_info(env, info, &count, args, NULL, NULL);
    if (count != 2 || napi_get_value_string_utf8(env, args[0], from, sizeof(from), &length) != napi_ok ||
        length >= sizeof(from) - 1 || napi_get_value_string_utf8(env, args[1], to, sizeof(to), &length) != napi_ok ||
        length >= sizeof(to) - 1) {
        napi_throw_type_error(env, NULL, "Expected two paths shorter than 4095 bytes");
        return NULL;
    }
    if (syscall(__NR_renameat2, AT_FDCWD, from, AT_FDCWD, to, 1) != 0) return fail(env, "Atomic publication failed");
    napi_get_undefined(env, &result);
    return result;
}

NAPI_MODULE_INIT() {
    napi_value lock, rename;
    napi_create_function(env, "tryLockExclusive", NAPI_AUTO_LENGTH, lock_file, NULL, &lock);
    napi_create_function(env, "publish", NAPI_AUTO_LENGTH, publish, NULL, &rename);
    napi_set_named_property(env, exports, "tryLockExclusive", lock);
    napi_set_named_property(env, exports, "publish", rename);
    return exports;
}
