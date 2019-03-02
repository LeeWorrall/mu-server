package io.muserver.acme;

import io.muserver.MuHandler;
import io.muserver.MuServer;
import io.muserver.SSLContextBuilder;

import javax.net.ssl.SSLContext;

class NoOpAcmeCertManager implements AcmeCertManager {
    @Override
    public void start(MuServer muServer) {

    }

    @Override
    public void stop() {

    }

    @Override
    public SSLContext createSSLContext() throws Exception {
        return SSLContextBuilder.unsignedLocalhostCert();
    }

    @Override
    public MuHandler createHandler() {
        return null;
    }
}
