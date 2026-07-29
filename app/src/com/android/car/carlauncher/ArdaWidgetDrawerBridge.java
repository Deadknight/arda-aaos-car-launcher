/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.carlauncher;

import java.lang.ref.WeakReference;

/** Connects the manifest receiver to the currently active home drawer. */
final class ArdaWidgetDrawerBridge {
    private static WeakReference<ArdaWidgetDrawerController> sController =
            new WeakReference<>(null);

    private ArdaWidgetDrawerBridge() {}

    static synchronized void attach(ArdaWidgetDrawerController controller) {
        sController = new WeakReference<>(controller);
    }

    static synchronized void detach(ArdaWidgetDrawerController controller) {
        if (sController.get() == controller) {
            sController.clear();
        }
    }

    static synchronized boolean toggle() {
        ArdaWidgetDrawerController controller = sController.get();
        if (controller == null) {
            return false;
        }
        controller.toggle();
        return true;
    }
}
