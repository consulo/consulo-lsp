import org.jspecify.annotations.NullMarked;

@NullMarked
module consulo.lsp.api {
    requires transitive consulo.ide.api;
    requires transitive consulo.application.api;
    requires transitive consulo.language.api;
    requires transitive consulo.language.editor.api;
    requires transitive consulo.process.api;
    requires transitive consulo.project.api;
    requires transitive consulo.virtual.file.system.api;

    requires transitive org.eclipse.lsp4j;
    requires transitive org.eclipse.lsp4j.jsonrpc;
    requires transitive com.google.gson;

    exports com.intellij.platform.lsp.api;
    exports com.intellij.platform.lsp.localize;
}
