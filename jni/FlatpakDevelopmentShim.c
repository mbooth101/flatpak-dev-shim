#include <jni.h>

#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>

#include <glib.h>
#include <glib-unix.h>
#include <gio/gio.h>
#include <gio/gunixfdlist.h>

#include "java_lang_ProcessImpl.h"

static JavaVM *jvm = NULL;

static void host_command_exited_cb(GDBusConnection *conn, const gchar *sender_name, const gchar *object_path,
        const gchar *interface_name, const gchar *signal_name, GVariant *parameters, gpointer user_data) {
    guint32 client_pid = 0;
    guint32 exit_status = 0;
    g_variant_get(parameters, "(uu)", &client_pid, &exit_status);

    JNIEnv *env = NULL;
    if ((*jvm)->GetEnv(jvm, (void**) &env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        (*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **) &env, NULL);
    }

    jobject process = user_data;
    jclass cls = (*env)->GetObjectClass(env, process);

    /*
     * Notify clients that the process exited and pass back the exit status
     */
    jmethodID pid_mid = (*env)->GetMethodID(env, cls, "pid", "()J");
    if (client_pid == (*env)->CallLongMethod(env, process, pid_mid)) {
        jmethodID exited_mid = (*env)->GetMethodID(env, cls, "hostCommandExited", "(I)V");
        (*env)->CallVoidMethod(env, process, exited_mid, exit_status);

        /*
         * Can disconnect from the HostCommandExited signal now
         */
        jfieldID subscription_fid = (*env)->GetFieldID(env, cls, "subscription", "I");
        guint exited_subscription = (*env)->GetIntField(env, process, subscription_fid);
        if (exited_subscription != 0) {
            g_dbus_connection_signal_unsubscribe(conn, exited_subscription);
            (*env)->SetIntField(env, process, subscription_fid, 0);
        }

        (*env)->DeleteGlobalRef(env, process);
    }
}

JNIEXPORT jint JNICALL Java_java_lang_ProcessImpl_execHostCommand(JNIEnv *env, jobject process, jobjectArray argv,
        jint argc, jobjectArray envv, jint envc, jbyteArray dir, jintArray fds) {
    GError *error = NULL;
    GDBusConnection *conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);

    (*env)->GetJavaVM(env, &jvm);

    /*
     * Build file descriptor map for the sandbox host process
     */
    GUnixFDList* fd_list = g_unix_fd_list_new();
    GVariantBuilder fds_builder;
    g_variant_builder_init(&fds_builder, G_VARIANT_TYPE("a{uh}"));

    jint *std_fds = (*env)->GetIntArrayElements(env, fds, NULL);
    gint stdout_pair[2] = { -1, -1 };
    gint stderr_pair[2] = { -1, -1 };
    gint stdin_pair[2] = { -1, -1 };

    /* stdin */
    if (std_fds[0] == -1) {
        g_unix_open_pipe(stdin_pair, FD_CLOEXEC, &error);
    } else {
        stdin_pair[0] = std_fds[0];
    }
    g_assert(stdin_pair[0] != -1);
    gint stdin_handle = g_unix_fd_list_append(fd_list, stdin_pair[0], &error);
    g_variant_builder_add(&fds_builder, "{uh}", 0, stdin_handle);

    /* stdout */
    if (std_fds[1] == -1) {
        g_unix_open_pipe(stdout_pair, FD_CLOEXEC, &error);
    } else {
        stdout_pair[1] = std_fds[1];
    }
    g_assert(stdout_pair[1] != -1);
    gint stdout_handle = g_unix_fd_list_append(fd_list, stdout_pair[1], &error);
    g_variant_builder_add(&fds_builder, "{uh}", 1, stdout_handle);

    /* stderr */
    if (std_fds[2] == -1) {
        g_unix_open_pipe(stderr_pair, FD_CLOEXEC, &error);
    } else {
        stderr_pair[1] = std_fds[2];
    }
    g_assert(stderr_pair[1] != -1);
    gint stderr_handle = g_unix_fd_list_append(fd_list, stderr_pair[1], &error);
    g_variant_builder_add(&fds_builder, "{uh}", 2, stderr_handle);

    /* Copy file descriptors for passing back to Java */
    std_fds[0] = stdin_pair[1];
    std_fds[1] = stdout_pair[0];
    std_fds[2] = stderr_pair[0];

    /*
     * Build the environment variable map
     */
    GVariantBuilder env_builder;
    g_variant_builder_init(&env_builder, G_VARIANT_TYPE("a{ss}"));
    const char *envs[envc];
    for (int i = 0; i < envc; i = i + 2) {
        jbyteArray key = (jbyteArray) (*env)->GetObjectArrayElement(env, envv, i);
        envs[i] = (char*) (*env)->GetByteArrayElements(env, key, NULL);
        jbyteArray value = (jbyteArray) (*env)->GetObjectArrayElement(env, envv, i + 1);
        envs[i + 1] = (char*) (*env)->GetByteArrayElements(env, value, NULL);
        g_variant_builder_add(&env_builder, "{ss}", envs[i], envs[i + 1]);
        (*env)->DeleteLocalRef(env, key);
        (*env)->DeleteLocalRef(env, value);
    }

    /*
     * Command and args list for the process, the Java should guarantee this is never null or empty
     */
    const char *args[argc + 1];
    for (int i = 0; i < argc; i++) {
        jbyteArray elem = (jbyteArray) (*env)->GetObjectArrayElement(env, argv, i);
        args[i] = (char*) (*env)->GetByteArrayElements(env, elem, NULL);
        (*env)->DeleteLocalRef(env, elem);
    }
    args[argc] = NULL; /* args must have a null final entry */

    /*
     * Working directory for the process, the Java should guarantee this is never null or empty
     */
    const char* workdir = (char*) (*env)->GetByteArrayElements(env, dir, NULL);

    /*
     * Connect to the HostCommandExited signal first to avoid races
     */
    guint exited_subscription = g_dbus_connection_signal_subscribe(conn, NULL, "org.freedesktop.Flatpak.Development",
            "HostCommandExited", "/org/freedesktop/Flatpak/Development", NULL, G_DBUS_SIGNAL_FLAGS_NONE,
            host_command_exited_cb, (*env)->NewGlobalRef(env, process), NULL);
    jclass cls = (*env)->GetObjectClass(env, process);
    jfieldID subscription_fid = (*env)->GetFieldID(env, cls, "subscription", "I");
    (*env)->SetIntField(env, process, subscription_fid, exited_subscription);

    /*
     * Call the HostCommand method to start a process on the sandbox host
     */
    GVariant* params = g_variant_new("(^ay^aay@a{uh}@a{ss}u)", workdir, args, g_variant_builder_end(&fds_builder),
            g_variant_builder_end(&env_builder), 0);
    GVariant* reply = g_dbus_connection_call_with_unix_fd_list_sync(conn, "org.freedesktop.Flatpak",
            "/org/freedesktop/Flatpak/Development", "org.freedesktop.Flatpak.Development", "HostCommand", params,
            G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, -1, fd_list, NULL, NULL, &error);

    /*
     * The pid of the process running on the sandbox host is returned in the reply
     */
    guint32 client_pid = -1;
    g_variant_get(reply, "(u)", &client_pid);

    /*
     * Clean up
     */
    g_object_unref(conn);
    for (int i = 0; i < argc; i++) {
        jbyteArray elem = (jbyteArray) (*env)->GetObjectArrayElement(env, argv, i);
        (*env)->ReleaseByteArrayElements(env, elem, (jbyte*) args[i], JNI_ABORT);
        (*env)->DeleteLocalRef(env, elem);
    }
    for (int i = 0; i < envc; i++) {
        jbyteArray elem = (jbyteArray) (*env)->GetObjectArrayElement(env, envv, i);
        (*env)->ReleaseByteArrayElements(env, elem, (jbyte*) envs[i], JNI_ABORT);
        (*env)->DeleteLocalRef(env, elem);
    }
    (*env)->ReleaseByteArrayElements(env, dir, (jbyte*) workdir, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, fds, std_fds, 0);
    return client_pid;
}
