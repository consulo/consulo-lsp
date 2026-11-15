import org.jspecify.annotations.NullMarked;

@NullMarked
module consulo.lsp {
    requires consulo.lsp.api;

    requires consulo.annotation;
    requires consulo.application.api;
    requires consulo.application.ui.api;
    requires consulo.code.editor.api;
    requires consulo.configurable.api;
    requires consulo.color.scheme.api;
    requires consulo.component.api;
    requires consulo.container.api;
    requires consulo.datacontext.api;
    requires consulo.disposer.api;
    requires consulo.document.api;
    requires consulo.execution.api;
    requires consulo.external.service.api;
    requires consulo.file.chooser.api;
    requires consulo.file.editor.api;
    requires consulo.find.api;
    requires consulo.ide.api;
    requires consulo.language.api;
    requires consulo.language.editor.api;
    requires consulo.language.editor.impl;
    requires consulo.language.editor.refactoring.api;
    requires consulo.language.editor.ui.api;
    requires consulo.language.impl;
    requires consulo.language.ui.api;
    requires consulo.localize.api;
    requires consulo.logging.api;
    requires consulo.module.content.api;
    requires consulo.navigation.api;
    requires consulo.process.api;
    requires consulo.project.api;
    requires consulo.project.ui.api;
    requires consulo.proxy;
    requires consulo.ui.api;
    requires consulo.ui.ex.api;
    requires consulo.ui.ex.awt.api;
    requires consulo.usage.api;
    requires consulo.util.collection;
    requires consulo.util.concurrent;
    requires consulo.util.dataholder;
    requires consulo.util.io;
    requires consulo.util.lang;
    requires consulo.util.xml.serializer;
    requires consulo.virtual.file.system.api;
    requires consulo.web.browser.api;

    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    requires com.google.gson;

    requires org.commonmark;
    requires org.commonmark.ext.gfm.tables;
    requires org.commonmark.ext.gfm.strikethrough;

    // LspIntegrationSettingsImpl state beans (LSP Servers settings)
    opens com.intellij.lsp.ui.frontend.settings to consulo.util.xml.serializer;
}
