package java.io;

public class DefaultFileSystem {

    public static FileSystem getFileSystem() {
        return new FlatpakFileSystem();
    }
}
