// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lsp.ui.frontend.settings;

import com.intellij.lsp.ui.frontend.localize.LspUiLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.Component;
import consulo.ui.InputBoxBuilder;
import consulo.ui.ListBox;
import consulo.ui.PseudoComponent;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.ex.action.ActionToolbarPosition;
import consulo.ui.ex.action.AnActionEvent;
import consulo.ui.ex.toolbar.AddAction;
import consulo.ui.ex.toolbar.DownMoveAction;
import consulo.ui.ex.toolbar.EditAction;
import consulo.ui.ex.toolbar.RemoveAction;
import consulo.ui.ex.toolbar.ToolbarDecoratorBuilderFactory;
import consulo.ui.ex.toolbar.UpMoveAction;
import consulo.ui.model.FlatDataModel;
import consulo.ui.model.MutableFlatDataModel;
import consulo.virtualFileSystem.fileType.FileNameMatcher;
import consulo.virtualFileSystem.fileType.FileNameMatcherFactory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@link PseudoComponent} over a unified {@link ListBox} decorated by {@link ToolbarDecoratorBuilderFactory}.
 * {@code ListBox} has no empty text, so {@code lsp.settings.server.file.patterns.empty} is not shown.
 */
public class LspPatternsPanel implements PseudoComponent {
    private final MutableFlatDataModel<FileNameMatcher> myModel = FlatDataModel.of(List.of());
    private final ListBox<FileNameMatcher> myList;
    private final List<FileNameMatcher> myPatterns = new ArrayList<>();
    private final Component myComponent;

    @RequiredUIAccess
    public LspPatternsPanel() {
        myList = ListBox.create(myModel);
        myList.setRenderer((presentation, item) -> {
            FileNameMatcher value = item.getValue();
            if (value != null) {
                presentation.append(value.getPresentableString());
            }
        });

        myComponent = ToolbarDecoratorBuilderFactory.getInstance()
            .create(myList)
            .addOrReplaceAction(new AddAction<FileNameMatcher>() {
                @Override
                @RequiredUIAccess
                protected void doAdd(AnActionEvent e) {
                    addPattern();
                }
            })
            .addOrReplaceAction(new EditAction<FileNameMatcher>() {
                @Override
                @RequiredUIAccess
                protected void doEdit(FileNameMatcher value, AnActionEvent e) {
                    editPattern();
                }
            })
            .addOrReplaceAction(new RemoveAction<FileNameMatcher>() {
                @Override
                @RequiredUIAccess
                protected void doRemove(FileNameMatcher value, AnActionEvent e) {
                    removePattern();
                }
            })
            .disableAction(UpMoveAction.class)
            .disableAction(DownMoveAction.class)
            .withToolbarPosition(ActionToolbarPosition.TOP)
            .build();
    }

    @Override
    public Component getComponent() {
        return myComponent;
    }

    public String getPatterns() {
        return myPatterns.stream().map(FileNameMatcher::getPresentableString).collect(Collectors.joining(";"));
    }

    @RequiredUIAccess
    public void setPatterns(String patternsString) {
        myPatterns.clear();
        List<FileNameMatcher> items = new ArrayList<>();

        if (!patternsString.isBlank()) {
            for (String pattern : patternsString.split(";")) {
                if (pattern.isBlank()) {
                    continue;
                }
                FileNameMatcher matcher = parsePattern(pattern);
                myPatterns.add(matcher);
                items.add(matcher);
            }
        }
        myModel.replaceAll(items);
    }

    @RequiredUIAccess
    private void addPattern() {
        editPattern(null);
    }

    @RequiredUIAccess
    private void editPattern() {
        FileNameMatcher item = myList.getValue();
        if (item != null) {
            editPattern(item);
        }
    }

    @RequiredUIAccess
    private void editPattern(@Nullable FileNameMatcher item) {
        LocalizeValue title = item == null
            ? LspUiLocalize.lspSettingsServerPatternAddTitle()
            : LspUiLocalize.lspSettingsServerPatternEditTitle();

        String oldPattern = item == null ? null : item.getPresentableString();
        InputBoxBuilder<String, ?> inputBox = InputBoxBuilder.text()
            .title(title)
            .text(LspUiLocalize.lspSettingsServerPatternPrompt());
        if (oldPattern != null) {
            inputBox.value(oldPattern);
        }

        // the input box is asynchronous
        UIAccess uiAccess = UIAccess.current();
        inputBox.showAsync(myList).thenAccept(pattern -> {
            if (pattern != null && !pattern.isBlank()) {
                uiAccess.give(() -> onPatternEntered(item, pattern));
            }
        });
    }

    @RequiredUIAccess
    private void onPatternEntered(@Nullable FileNameMatcher item, String pattern) {
        FileNameMatcher matcher = parsePattern(pattern);
        if (item != null) {
            int index = myPatterns.indexOf(item);
            if (index >= 0) {
                myPatterns.set(index, matcher);
                myModel.replaceAll(new ArrayList<>(myPatterns));
            }
        }
        else {
            myPatterns.add(matcher);
            myModel.add(matcher);
        }
        myList.setValue(matcher);
    }

    @RequiredUIAccess
    private void removePattern() {
        FileNameMatcher removed = myList.getValue();
        if (removed == null) {
            return;
        }
        myPatterns.remove(removed);
        myModel.remove(removed);
    }

    public static FileNameMatcher parsePattern(String pattern) {
        return FileNameMatcherFactory.getInstance().createMatcher(pattern);
    }
}
