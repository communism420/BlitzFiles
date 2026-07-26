package com.blitzfiles.app.provider.remote;

import com.blitzfiles.app.provider.common.ParcelableFileTime;
import com.blitzfiles.app.provider.common.ParcelablePosixFileMode;
import com.blitzfiles.app.provider.common.PosixGroup;
import com.blitzfiles.app.provider.common.PosixUser;
import com.blitzfiles.app.provider.remote.ParcelableException;
import com.blitzfiles.app.provider.remote.ParcelableObject;

interface IRemotePosixFileAttributeView {
    ParcelableObject readAttributes(out ParcelableException exception);

    void setTimes(
        in ParcelableFileTime lastModifiedTime,
        in ParcelableFileTime lastAccessTime,
        in ParcelableFileTime createTime,
        out ParcelableException exception
    );

    void setOwner(in PosixUser owner, out ParcelableException exception);

    void setGroup(in PosixGroup group, out ParcelableException exception);

    void setMode(in ParcelablePosixFileMode mode, out ParcelableException exception);

    void setSeLinuxContext(in ParcelableObject context, out ParcelableException exception);

    void restoreSeLinuxContext(out ParcelableException exception);
}
