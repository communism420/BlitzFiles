package com.blitzfiles.app.provider.remote;

import com.blitzfiles.app.provider.remote.ParcelableException;

interface IRemoteFileSystem {
    void close(out ParcelableException exception);
}
