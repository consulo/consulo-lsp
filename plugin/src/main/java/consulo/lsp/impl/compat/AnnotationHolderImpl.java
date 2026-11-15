// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.lsp.impl.compat;

import consulo.annotation.access.RequiredReadAction;
import consulo.codeEditor.markup.GutterIconRenderer;
import consulo.colorScheme.TextAttributes;
import consulo.colorScheme.TextAttributesKey;
import consulo.document.util.TextRange;
import consulo.language.Language;
import consulo.language.ast.ASTNode;
import consulo.language.editor.annotation.Annotation;
import consulo.language.editor.annotation.AnnotationBuilder;
import consulo.language.editor.annotation.AnnotationHolder;
import consulo.language.editor.annotation.AnnotationSession;
import consulo.language.editor.annotation.HighlightSeverity;
import consulo.language.editor.annotation.ProblemGroup;
import consulo.language.editor.inspection.LocalQuickFix;
import consulo.language.editor.inspection.ProblemDescriptor;
import consulo.language.editor.inspection.ProblemHighlightType;
import consulo.language.editor.intention.IntentionAction;
import consulo.language.editor.rawHighlight.HighlightDisplayKey;
import consulo.language.psi.PsiElement;
import consulo.localize.LocalizeValue;
import consulo.util.lang.xml.XmlStringUtil;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Consulo: a minimal port of IntelliJ's {@code com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl}.
 * Consulo's own {@code AnnotationHolderImpl} is internal, and the LSP client creates annotations outside an
 * {@link consulo.language.editor.annotation.Annotator} (it converts LSP diagnostics with
 * {@code LspDiagnosticsSupport.createAnnotation}). The holder is a list of the created annotations.
 */
public class AnnotationHolderImpl extends ArrayList<Annotation> implements AnnotationHolder {
    private static final Function<String, String> TOOLTIP_ESCAPE =
        message -> XmlStringUtil.wrapInHtml(XmlStringUtil.escapeText(message));

    private final AnnotationSession mySession;
    private final boolean myBatchMode;
    private @Nullable PsiElement myCurrentElement;

    public AnnotationHolderImpl(AnnotationSession session, boolean batchMode) {
        mySession = session;
        myBatchMode = batchMode;
    }

    /**
     * Sets the element whose text range is used by builders that do not set a range explicitly.
     */
    public void runAnnotatorWithContext(PsiElement element) {
        myCurrentElement = element;
    }

    @Override
    public AnnotationSession getCurrentAnnotationSession() {
        return mySession;
    }

    @Override
    public boolean isBatchMode() {
        return myBatchMode;
    }

    private Language getLanguage() {
        return mySession.getFile().getLanguage();
    }

    @Override
    public AnnotationBuilder newOfSeverity(HighlightSeverity severity, LocalizeValue message) {
        return new Builder(severity, message);
    }

    @Override
    public AnnotationBuilder newSilentAnnotation(HighlightSeverity severity) {
        return new Builder(severity, LocalizeValue.empty());
    }

    @Override
    @Deprecated
    public Annotation createErrorAnnotation(PsiElement elt, @Nullable String message) {
        return createAnnotation(HighlightSeverity.ERROR, elt.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createErrorAnnotation(ASTNode node, @Nullable String message) {
        return createAnnotation(HighlightSeverity.ERROR, node.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createErrorAnnotation(TextRange range, @Nullable String message) {
        return createAnnotation(HighlightSeverity.ERROR, range, message);
    }

    @Override
    @Deprecated
    @RequiredReadAction
    public Annotation createWarningAnnotation(PsiElement elt, @Nullable String message) {
        return createAnnotation(HighlightSeverity.WARNING, elt.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createWarningAnnotation(ASTNode node, @Nullable String message) {
        return createAnnotation(HighlightSeverity.WARNING, node.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createWarningAnnotation(TextRange range, @Nullable String message) {
        return createAnnotation(HighlightSeverity.WARNING, range, message);
    }

    @Override
    @Deprecated
    @RequiredReadAction
    public Annotation createWeakWarningAnnotation(PsiElement elt, @Nullable String message) {
        return createAnnotation(HighlightSeverity.WEAK_WARNING, elt.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createWeakWarningAnnotation(ASTNode node, @Nullable String message) {
        return createAnnotation(HighlightSeverity.WEAK_WARNING, node.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createWeakWarningAnnotation(TextRange range, @Nullable String message) {
        return createAnnotation(HighlightSeverity.WEAK_WARNING, range, message);
    }

    @Override
    @Deprecated
    @RequiredReadAction
    public Annotation createInfoAnnotation(PsiElement elt, @Nullable String message) {
        return createAnnotation(HighlightSeverity.INFORMATION, elt.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createInfoAnnotation(ASTNode node, @Nullable String message) {
        return createAnnotation(HighlightSeverity.INFORMATION, node.getTextRange(), message);
    }

    @Override
    @Deprecated
    public Annotation createInfoAnnotation(TextRange range, @Nullable String message) {
        return createAnnotation(HighlightSeverity.INFORMATION, range, message);
    }

    @Override
    @Deprecated
    public Annotation createAnnotation(HighlightSeverity severity, TextRange range, @Nullable String message) {
        String tooltip = message == null ? null : TOOLTIP_ESCAPE.apply(message);
        return createAnnotation(severity, range, message, tooltip);
    }

    @Override
    @Deprecated
    public Annotation createAnnotation(
        HighlightSeverity severity,
        TextRange range,
        @Nullable String message,
        @Nullable String htmlTooltip
    ) {
        Annotation annotation = new Annotation(
            range.getStartOffset(),
            range.getEndOffset(),
            severity,
            message == null ? LocalizeValue.empty() : LocalizeValue.of(message),
            htmlTooltip == null ? LocalizeValue.empty() : LocalizeValue.of(htmlTooltip),
            getLanguage()
        );
        add(annotation);
        return annotation;
    }

    private static <T extends IntentionAction & LocalQuickFix> void registerBatchFix(
        Annotation annotation,
        Object fix,
        TextRange range,
        @Nullable HighlightDisplayKey key
    ) {
        //noinspection unchecked
        annotation.registerBatchFix((T) fix, range, key);
    }

    private final class Builder implements AnnotationBuilder {
        private final HighlightSeverity mySeverity;
        private final LocalizeValue myMessage;
        private LocalizeValue myTooltip = LocalizeValue.empty();
        private @Nullable TextRange myRange;
        private @Nullable Boolean myAfterEndOfLine;
        private @Nullable Boolean myFileLevel;
        private @Nullable GutterIconRenderer myGutterIconRenderer;
        private @Nullable ProblemGroup myProblemGroup;
        private @Nullable TextAttributes myEnforcedAttributes;
        private @Nullable TextAttributesKey myTextAttributesKey;
        private @Nullable ProblemHighlightType myHighlightType;
        private @Nullable Boolean myNeedsUpdateOnTyping;
        private final List<Fix> myFixes = new ArrayList<>();
        private boolean myCreated;

        private Builder(HighlightSeverity severity, LocalizeValue message) {
            mySeverity = severity;
            myMessage = message;
        }

        @Override
        public AnnotationBuilder range(TextRange range) {
            myRange = range;
            return this;
        }

        @Override
        public AnnotationBuilder range(ASTNode element) {
            myRange = element.getTextRange();
            return this;
        }

        @Override
        public AnnotationBuilder range(PsiElement element) {
            myRange = element.getTextRange();
            return this;
        }

        @Override
        public AnnotationBuilder afterEndOfLine() {
            myAfterEndOfLine = true;
            return this;
        }

        @Override
        public AnnotationBuilder fileLevel() {
            myFileLevel = true;
            return this;
        }

        @Override
        public AnnotationBuilder gutterIconRenderer(GutterIconRenderer gutterIconRenderer) {
            myGutterIconRenderer = gutterIconRenderer;
            return this;
        }

        @Override
        public AnnotationBuilder problemGroup(ProblemGroup problemGroup) {
            myProblemGroup = problemGroup;
            return this;
        }

        @Override
        public AnnotationBuilder enforcedTextAttributes(TextAttributes enforcedAttributes) {
            myEnforcedAttributes = enforcedAttributes;
            return this;
        }

        @Override
        public AnnotationBuilder textAttributes(TextAttributesKey enforcedAttributes) {
            myTextAttributesKey = enforcedAttributes;
            return this;
        }

        @Override
        public AnnotationBuilder highlightType(ProblemHighlightType highlightType) {
            myHighlightType = highlightType;
            return this;
        }

        @Override
        public AnnotationBuilder tooltip(LocalizeValue tooltip) {
            myTooltip = tooltip;
            return this;
        }

        @Override
        public AnnotationBuilder needsUpdateOnTyping() {
            return needsUpdateOnTyping(true);
        }

        @Override
        public AnnotationBuilder needsUpdateOnTyping(boolean value) {
            myNeedsUpdateOnTyping = value;
            return this;
        }

        @Override
        public AnnotationBuilder withFix(IntentionAction fix) {
            myFixes.add(new Fix(fix, null));
            return this;
        }

        @Override
        public FixBuilder newFix(IntentionAction fix) {
            return new Fix(fix, null);
        }

        @Override
        public FixBuilder newLocalQuickFix(LocalQuickFix fix, ProblemDescriptor problemDescriptor) {
            return new Fix(fix, problemDescriptor);
        }

        @Override
        @RequiredReadAction
        public void create() {
            createAnnotation();
        }

        @Override
        @RequiredReadAction
        @SuppressWarnings("deprecation")
        public Annotation createAnnotation() {
            if (myCreated) {
                throw new IllegalStateException("Must not call .create() twice");
            }
            myCreated = true;
            TextRange range = myRange;
            if (range == null) {
                PsiElement element = myCurrentElement != null ? myCurrentElement : mySession.getFile();
                range = element.getTextRange();
            }

            LocalizeValue tooltip = myTooltip;
            if (tooltip.isEmpty() && myMessage.isNotEmpty()) {
                tooltip = myMessage.map(TOOLTIP_ESCAPE);
            }

            Annotation annotation = new Annotation(range.getStartOffset(), range.getEndOffset(), mySeverity, myMessage, tooltip, getLanguage());
            if (myNeedsUpdateOnTyping != null) {
                annotation.setNeedsUpdateOnTyping(myNeedsUpdateOnTyping);
            }
            if (myHighlightType != null) {
                annotation.setHighlightType(myHighlightType);
            }
            if (myTextAttributesKey != null) {
                annotation.setTextAttributes(myTextAttributesKey);
            }
            if (myEnforcedAttributes != null) {
                annotation.setEnforcedTextAttributes(myEnforcedAttributes);
            }
            if (myProblemGroup != null) {
                annotation.setProblemGroup(myProblemGroup);
            }
            if (myGutterIconRenderer != null) {
                annotation.setGutterIconRenderer(myGutterIconRenderer);
            }
            if (myFileLevel != null) {
                annotation.setFileLevelAnnotation(myFileLevel);
            }
            if (myAfterEndOfLine != null) {
                annotation.setAfterEndOfLine(myAfterEndOfLine);
            }
            for (Fix fix : myFixes) {
                TextRange fixRange = fix.myFixRange != null ? fix.myFixRange : range;
                if (fix.myProblemDescriptor != null) {
                    annotation.registerFix((LocalQuickFix) fix.myFix, fixRange, fix.myKey, fix.myProblemDescriptor);
                }
                else if (fix.myBatch) {
                    registerBatchFix(annotation, fix.myFix, fixRange, fix.myKey);
                }
                else if (fix.myUniversal) {
                    registerBatchFix(annotation, fix.myFix, fixRange, fix.myKey);
                    annotation.registerFix((IntentionAction) fix.myFix, fixRange, fix.myKey);
                }
                else {
                    annotation.registerFix((IntentionAction) fix.myFix, fixRange, fix.myKey);
                }
            }
            add(annotation);
            return annotation;
        }

        private final class Fix implements FixBuilder {
            private final Object myFix;
            private final @Nullable ProblemDescriptor myProblemDescriptor;
            private @Nullable TextRange myFixRange;
            private @Nullable HighlightDisplayKey myKey;
            private boolean myBatch;
            private boolean myUniversal;

            private Fix(Object fix, @Nullable ProblemDescriptor problemDescriptor) {
                myFix = fix;
                myProblemDescriptor = problemDescriptor;
            }

            @Override
            public FixBuilder range(TextRange range) {
                myFixRange = range;
                return this;
            }

            @Override
            public FixBuilder key(HighlightDisplayKey key) {
                myKey = key;
                return this;
            }

            @Override
            public FixBuilder batch() {
                myBatch = true;
                return this;
            }

            @Override
            public FixBuilder universal() {
                myUniversal = true;
                return this;
            }

            @Override
            public AnnotationBuilder registerFix() {
                myFixes.add(this);
                return Builder.this;
            }
        }
    }
}
