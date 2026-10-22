package com.insightface.sdk.inspireface.base;

public class FaceEmotionResult {
    public int num;             ///< Number of faces detected.
    public int[] emotion;       ///< Emotion of the detected face.
                                ///< 0: Neutral;
                                ///< 1: Happy;
                                ///< 2: Sad;
                                ///< 3: Surprise;
                                ///< 4: Fear;
                                ///< 5: Disgust;
                                ///< 6: Anger;
}
