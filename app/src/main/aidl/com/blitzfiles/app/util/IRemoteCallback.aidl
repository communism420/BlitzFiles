package com.blitzfiles.app.util;

import android.os.Bundle;

interface IRemoteCallback {
    void sendResult(in Bundle result);
}
