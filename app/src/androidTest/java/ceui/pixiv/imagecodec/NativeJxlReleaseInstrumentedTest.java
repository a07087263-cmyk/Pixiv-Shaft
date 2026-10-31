package ceui.pixiv.imagecodec;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

/** Release/R8 probe without cross-APK Kotlin stdlib references. */
@RunWith(AndroidJUnit4.class)
public class NativeJxlReleaseInstrumentedTest {
    @Test
    public void animatedEncodingAndFullFrameValidationSurviveR8() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File input = new File(context.getCacheDir(), "release-jxl-input");
        input.mkdirs();
        File firstFile = new File(input, "0.png");
        File secondFile = new File(input, "1.png");
        writeFrame(firstFile, 0);
        writeFrame(secondFile, 70);
        List<File> frames = Arrays.asList(firstFile, secondFile);
        List<Integer> delays = Arrays.asList(40, 70);
        File jxl = new File(context.getCacheDir(), "release-animation.jxl");
        NativeImageCodec.encodeJxlAnimation(frames, delays, jxl, 5);
        File output = new File(context.getCacheDir(), "release-jxl-output");
        DecodedJxlAnimation decoded = NativeImageCodec.decodeJxlAnimation(jxl, output);
        assertEquals(delays, decoded.getDelaysMs());
        assertEquals(2, decoded.getFiles().size());
        Bitmap frame = BitmapFactory.decodeFile(decoded.getFiles().get(0).getAbsolutePath());
        assertEquals(Color.rgb(3, 5, 8), frame.getPixel(3, 5));
        frame.recycle();
    }

    private static void writeFrame(File file, int offset) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(17, 19, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                bitmap.setPixel(x, y, Color.rgb(x + offset, y + offset, (x + y + offset) & 255));
            }
        }
        // Pixel (3,5) is (3,5,8) for the first frame.
        FileOutputStream output = new FileOutputStream(file);
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally {
            output.close();
            bitmap.recycle();
        }
    }
}
