#include <stdlib.h>
#include <unistd.h>

#include <glib.h>
#include <glib-unix.h>
#include <gio/gio.h>
#include <gio/gunixfdlist.h>

// TODO send term/kill signal to process depending on force:
// <method name="HostCommandSignal">
// <arg type='u' name='pid' direction='in'/>
// <arg type='u' name='signal' direction='in'/>
// <arg type='b' name='to_process_group' direction='in'/>
// </method>

typedef struct CommandData {
    guint32 pid;
    guint32 status;
    guint32 subscription;
} CommandData;

static GMainLoop *loop;

/**
 * This is called when the process running on the sandbox host has exited, in response to the HostCommandExited dbus
 * signal.
 */
static void command_exited_cb(GDBusConnection *conn, const gchar *sender_name, const gchar *object_path,
        const gchar *interface_name, const gchar *signal_name, GVariant *parameters, gpointer user_data) {

    guint32 client_pid = 0;
    guint32 exit_status = 0;
    g_variant_get(parameters, "(uu)", &client_pid, &exit_status);

    CommandData *command_data = (CommandData *) user_data;

    /* Note the exit status and compel the main loop to break */
    if (client_pid == command_data->pid) {
        command_data->status = exit_status;

        /* Can disconnect from the HostCommandExited signal now too */
        if (command_data->subscription != 0) {
            g_dbus_connection_signal_unsubscribe(conn, command_data->subscription);
        }

        g_main_loop_quit(loop);
    }
}

/**
 * Starts the process on the sandbox host and blocks until the process has exited. The return value is the exit code
 * of that process.
 */
static int exec_host_command(const char *workdir, const char *argv[], int argc, const char *envv[], int envc) {

    CommandData *command_data = calloc(1, sizeof(CommandData));

    GError *error = NULL;
    GDBusConnection *conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);

    /* Ensure the args list has a null final entry */
    const char *args[argc + 1];
    for (int i = 0; i < argc; i++) {
        args[i] = argv[i];
    }
    args[argc] = NULL;

    /* Build file descriptor map for the sandbox host process */
    GUnixFDList* fd_list = g_unix_fd_list_new();
    GVariantBuilder fds_builder;
    g_variant_builder_init(&fds_builder, G_VARIANT_TYPE("a{uh}"));
    for (int i = 0; i < 3; i++) {
        gint handle = g_unix_fd_list_append(fd_list, i, &error);
        g_variant_builder_add(&fds_builder, "{uh}", i, handle);
    }

    /* Build the environment variable map */
    GVariantBuilder env_builder;
    g_variant_builder_init(&env_builder, G_VARIANT_TYPE("a{ss}"));
    for (int i = 0; i < envc; i += 2) {
        g_variant_builder_add(&env_builder, "{ss}", envv[i], envv[i + 1]);
    }

    /* Connect to the HostCommandExited dbus signal first to avoid races */
    guint32 sub_id = g_dbus_connection_signal_subscribe(conn, NULL, "org.freedesktop.Flatpak.Development",
            "HostCommandExited", "/org/freedesktop/Flatpak/Development", NULL, G_DBUS_SIGNAL_FLAGS_NONE,
            command_exited_cb, command_data, NULL);
    command_data->subscription = sub_id;

    /* Call the HostCommand dbus method to start a process on the sandbox host */
    GVariant* params = g_variant_new("(^ay^aay@a{uh}@a{ss}u)", workdir, args, g_variant_builder_end(&fds_builder),
            g_variant_builder_end(&env_builder), 0);
    GVariant* reply = g_dbus_connection_call_with_unix_fd_list_sync(conn, "org.freedesktop.Flatpak",
            "/org/freedesktop/Flatpak/Development", "org.freedesktop.Flatpak.Development", "HostCommand", params,
            G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, -1, fd_list, NULL, NULL, &error);

    /* The pid of the process running on the sandbox host is returned in the reply */
    g_variant_get(reply, "(u)", &command_data->pid);

    loop = g_main_loop_new(NULL, FALSE);
    g_main_loop_run(loop);

    /*
     * The exit status will have been noted by the callback notifying us that the host process exited, but is not in
     * the lower 8 bits that is required for use as an exit code, hence the right shift.
     */
    int status = command_data->status >> 8;

    g_object_unref(conn);
    free(command_data);

    return status;
}

int main(int argc, const char *argv[]) {
    return exec_host_command(argv[1], argv + 2, argc - 2, NULL, 0);
}
