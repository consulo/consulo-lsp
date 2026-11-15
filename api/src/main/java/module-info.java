import org.jspecify.annotations.NullMarked;

@NullMarked
module consulo.lsp.api {
    requires transitive consulo.ide.api;
    requires transitive consulo.annotation;
    requires transitive consulo.application.api;
    requires transitive consulo.base.icon.library;
    requires transitive consulo.code.editor.api;
    requires transitive consulo.color.scheme.api;
    requires transitive consulo.component.api;
    requires transitive consulo.disposer.api;
    requires transitive consulo.document.api;
    requires transitive consulo.file.editor.api;
    requires transitive consulo.language.api;
    requires transitive consulo.language.editor.api;
    requires transitive consulo.localize.api;
    requires transitive consulo.logging.api;
    requires transitive consulo.module.content.api;
    requires transitive consulo.navigation.api;
    requires transitive consulo.process.api;
    requires transitive consulo.project.api;
    requires transitive consulo.ui.api;
    requires transitive consulo.ui.ex.api;
    requires transitive consulo.ui.ex.awt.api;
    requires transitive consulo.util.dataholder;
    requires transitive consulo.util.io;
    requires transitive consulo.util.lang;
    requires transitive consulo.virtual.file.system.api;

    requires transitive java.desktop;

    requires transitive org.eclipse.lsp4j;
    requires transitive org.eclipse.lsp4j.jsonrpc;
    requires transitive com.google.gson;

    exports com.intellij.platform.lsp.api;
    exports com.intellij.platform.lsp.api.customization;
    exports com.intellij.platform.lsp.util;
    exports com.intellij.platform.lsp.localize;
    exports com.intellij.lsp.ui.frontend.localize;
    exports consulo.lsp.compat;

    // gson serializes the lsp4j subclasses of the API (e.g. SignatureInformationCapabilitiesWithNoActiveParameterSupport)
    opens com.intellij.platform.lsp.api to com.google.gson;
}
