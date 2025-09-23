package org.bytedeco.javacv;

import javafx.scene.image.Image;

public   class FrameConverter {
    private static final JavaFXFrameConverter frameConverter= new JavaFXFrameConverter();
    public static Image convert(Frame frame) {
        return frameConverter.convert(frame);

    }

}
