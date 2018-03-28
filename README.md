# Flatpak Development Shim

A shim that allows Java programs to breakout of a Flatpak sandbox and spawn processes on the sandbox host. It is built as a Java 9 Jigsaw-style module patch to override Java's `java.lang.ProcessBuilder` functionality and spawns processes on the sandbox host by communicating with the "Development" interface of the org.freedesktop.Flatpak DBus API.

## Usage

In order to enable the shim, Java programs must be started with the following parameters:

    --patch-module java.base=/path/to/flatpak-dev-shim.jar
    -Dsun.boot.library.path=/usr/lib/jvm/java-9/lib:/path/to/flatpak-dev-shim/libdir

And the Flatpak sandbox in which the Java program is running must be granted the following permissions:

    --filesystem=host --allow=devel --talk-name=org.freedesktop.Flatpak

Use `java.lang.ProcessBuilder` in the usual way.

## Building

This project is built using maven and requires Java 9. To build the native parts successfully, `JAVA_HOME` must be set in the environment, for example:

    JAVA_HOME=/usr/lib/jvm/java-9 mvn clean verify

To use system-specific compiler and linker flags when building the native parts, you can also set `CFLAGS` and `LDFLAGS` in the environment.

## Limitations

* Because the spawned process runs outside the sandbox, it is not visible to Java (it is outside of the sandbox's cgroup). This means we can't enumerate the process's children and traverse the process hierarchy, etc.
* Currently works only with SWT applications because it relies on SWT to work the message pump that triggers the DBus callbacks.

## To Do

* Killing processes on command
* Killing processes on exit

## Problems

Some problems encountered during development:

* Maven compiler plugin is unable to take the JDK 9 option "--patch-module" as descrete arguments, see: https://issues.apache.org/jira/browse/MCOMPILER-311
