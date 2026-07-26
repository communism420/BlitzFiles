package com.blitzfiles.app.provider.remote;

import com.blitzfiles.app.provider.remote.ParcelableException;
import com.blitzfiles.app.util.RemoteCallback;

interface IRemotePathObservable {
    void addObserver(in RemoteCallback observer);

    void close(out ParcelableException exception);
}
