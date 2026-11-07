package ru.main.explorer;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Explicit opt-in real-provider smoke test; no fixture may replace network failure. */
@RunWith(org.robolectric.RobolectricTestRunner.class) @Config(sdk=34,qualifiers="mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class LiveOamImageryTest {
    static void settle(ImageryTraceActivity a) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();if(!a.busy)return;Thread.sleep(25);}
        fail("Real OAM operation timed out: "+a.status.getText());
    }
    @Test public void realProviderThroughActivityToSavedCandidate()throws Exception{
        Assume.assumeTrue("Explicit network opt-in required", "1".equals(System.getenv("MAPNOTES_OAM_LIVE")));
        ActivityController<ImageryTraceActivity> ctrl=Robolectric.buildActivity(ImageryTraceActivity.class,new Intent().putExtra("latitude",55.814).putExtra("longitude",37.425).putExtra("zoom",15)).setup();
        ImageryTraceActivity a=ctrl.get();MapNetwork.setEnabled(a,true);
        try{
            a.coverage.layout(0,0,540,600);a.search();settle(a);assertNotNull(a.coverageResult);a.listCoverage(a.coverageResult.items);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("Real search must offer eligible source; status: "+a.status.getText(),dialog);
            int selected=-1;
            for(int i=0;i<dialog.getListView().getAdapter().getCount();i++)if(dialog.getListView().getAdapter().getItem(i).toString().contains("Holland park 2022 November v2")){selected=i;break;}
            assertTrue("Known source absent from live provider; no mock fallback",selected>=0);
            dialog.getListView().performItemClick(null,selected,selected);ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();assertNotNull(a.coverage.selected);
            File coverageOutput=new File("build/oam-live-evidence");assertTrue(coverageOutput.isDirectory()||coverageOutput.mkdirs());
            View coverageScreen=a.getWindow().getDecorView();coverageScreen.measure(View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1100,View.MeasureSpec.EXACTLY));coverageScreen.layout(0,0,540,1100);Bitmap coverageBitmap=Bitmap.createBitmap(540,1100,Bitmap.Config.ARGB_8888);coverageScreen.draw(new Canvas(coverageBitmap));try(FileOutputStream out=new FileOutputStream(new File(coverageOutput,"actual-coverage-screen.png"))){assertTrue(coverageBitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
            OamImageryTest.button(a,"imagery_load_selection").performClick();settle(a);
            assertNotNull("Real raster download/decode failed: "+a.status.getText(),a.raster);
            assertEquals("6383e049fba1230007216ec4",a.raster.source.id);
            assertEquals(512,a.raster.bitmap.getWidth());
            a.points[0][0]=165;a.points[0][1]=381;
            a.points[1][0]=435;a.points[1][1]=241;
            a.points[2][0]=330;a.points[2][1]=263;
            a.runTrace();settle(a);assertNotNull("Candidate missing: "+a.status.getText(),a.candidate);
            a.saveDraft();settle(a);assertNotNull("Save failed: "+a.status.getText(),a.savedId);
            String id=a.savedId;a.clearImage();a.openDraft(id);settle(a);assertNotNull(a.candidate);assertNotNull(a.exportJson);
            File output=new File("build/oam-live-evidence");assertTrue(output.isDirectory()||output.mkdirs());
            try(FileOutputStream out=new FileOutputStream(new File(output,"actual-raster.png"))){assertTrue(a.raster.bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
            View content=a.getWindow().getDecorView();int w=540,h=1100;
            content.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));content.layout(0,0,w,h);
            Bitmap screen=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);content.draw(new Canvas(screen));
            try(FileOutputStream out=new FileOutputStream(new File(output,"actual-beta-screen.png"))){assertTrue(screen.compress(Bitmap.CompressFormat.PNG,100,out));}
            try(FileOutputStream out=new FileOutputStream(new File(output,"actual-candidate.geojson"))){out.write(a.exportJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            System.out.println("LIVE OAM OK: real STAC + real 512px raster + trace + save + reopen; vertices="+a.candidate.vertices.size()+" source="+a.raster.source.id);
        }finally{ctrl.pause().stop().destroy();}
    }
}
