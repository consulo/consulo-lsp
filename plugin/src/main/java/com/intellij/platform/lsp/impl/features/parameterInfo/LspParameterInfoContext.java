package com.intellij.platform.lsp.impl.features.parameterInfo;

import com.intellij.platform.lsp.impl.LspClientImpl;
import org.eclipse.lsp4j.SignatureHelp;

public class LspParameterInfoContext {
    // The latest response to the `textDocument/signatureHelp` request; updated in `LspParameterInfoHandler.updateParameterInfo`
    private volatile SignatureHelp mySignatureHelp;
    private final LspClientImpl myClient;

    public LspParameterInfoContext(SignatureHelp signatureHelp, LspClientImpl client) {
        mySignatureHelp = signatureHelp;
        myClient = client;
    }

    public SignatureHelp getSignatureHelp() {
        return mySignatureHelp;
    }

    public void setSignatureHelp(SignatureHelp signatureHelp) {
        mySignatureHelp = signatureHelp;
    }

    public LspClientImpl getClient() {
        return myClient;
    }
}
