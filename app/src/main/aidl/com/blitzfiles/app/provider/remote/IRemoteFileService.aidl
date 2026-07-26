package com.blitzfiles.app.provider.remote;

import com.blitzfiles.app.provider.remote.IRemoteFileSystem;
import com.blitzfiles.app.provider.remote.IRemoteFileSystemProvider;
import com.blitzfiles.app.provider.remote.IRemotePosixFileAttributeView;
import com.blitzfiles.app.provider.remote.IRemotePosixFileStore;
import com.blitzfiles.app.provider.remote.ParcelableObject;

interface IRemoteFileService {
    IRemoteFileSystemProvider getRemoteFileSystemProviderInterface(String scheme);

    IRemoteFileSystem getRemoteFileSystemInterface(in ParcelableObject fileSystem);

    IRemotePosixFileStore getRemotePosixFileStoreInterface(in ParcelableObject fileStore);

    IRemotePosixFileAttributeView getRemotePosixFileAttributeViewInterface(
        in ParcelableObject attributeView
    );
}
