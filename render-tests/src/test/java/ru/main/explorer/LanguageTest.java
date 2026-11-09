package ru.main.explorer;

import android.app.*;
import android.content.*;
import android.content.res.*;
import android.os.LocaleList;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LanguageTest {
    Context app;

    @Before
    public void setup() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(Language.PREFS, 0).edit().clear().commit();
    }

    @After
    public void clean() {
        app.getSharedPreferences(Language.PREFS, 0).edit().clear().commit();
    }

    Context device(String language) {
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(new LocaleList(Locale.forLanguageTag(language)));
        return app.createConfigurationContext(config);
    }

    @Test
    public void systemRussianUsesRussianResources() {
        Context ru = device("ru");
        assertSame(ru, Language.wrap(ru));
        assertEquals("Начать прогулку", Language.text(Language.wrap(ru), "start_walk"));
    }

    @Test
    public void unsupportedDeviceLanguageFallsBackToEnglish() {
        assertEquals("Start walk", Language.text(Language.wrap(device("de")), "start_walk"));
    }

    @Test
    public void overridePersistsAcrossNewContexts() {
        Language.select(app, "en");
        assertEquals("Start walk", Language.text(Language.wrap(device("ru")), "start_walk"));
        assertEquals("en", Language.selected(device("ru")));
        Language.select(app, "ru");
        assertEquals("Начать прогулку", Language.text(Language.wrap(device("en")), "start_walk"));
        Language.select(app, "system");
        assertEquals("Start walk", Language.text(Language.wrap(device("en")), "start_walk"));
    }

    @Test
    public void onlyKnownLabelsTranslateNotUserNotes() {
        Language.select(app, "en");
        Context c = Language.wrap(app);
        assertEquals("My photos", Language.label(c, "Мои фото"));
        assertEquals("Моя заметка на русском", Language.label(c, "Моя заметка на русском"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidChoiceCannotBeStored() {
        Language.select(app, "xx");
    }

    @Test
    public void mainNavigationUsesExplicitLanguage() {
        Language.select(app, "en");
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).create();
        try {
            MainActivity activity = controller.get();
            assertEquals(
                    "Start walk",
                    activity.button(Language.text(activity, R.string.start_walk))
                            .getText()
                            .toString());
            assertEquals("App language", Language.text(activity, "language"));
        } finally {
            controller.destroy();
        }
    }

    @Test
    @Config(qualifiers = "ru")
    public void serviceReturnsToSystemLanguageWithoutActivity() {
        Language.select(app, "en");
        org.robolectric.android.controller.ServiceController<WalkService> controller =
                Robolectric.buildService(WalkService.class).create();
        try {
            WalkService service = controller.get();
            assertEquals("Stop", service.notification().actions[0].title.toString());
            Language.select(app, "system");
            assertEquals("Стоп", service.notification().actions[0].title.toString());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void newActivityReadsPersistedChoiceAndNotesRemainUntouched() {
        String note = "Начать прогулку / Start walk";
        try (Store db = new Store(app)) {
            android.location.Location fix = new android.location.Location("synthetic");
            fix.setLatitude(0);
            fix.setLongitude(0);
            long id = db.addPhoto("synthetic-missing.jpg", fix, 1000);
            db.saveNote(id, note);
            for (String code : new String[] {"en", "ru", "en"}) {
                Language.select(app, code);
                org.robolectric.android.controller.ActivityController<PhotoActivity> c =
                        Robolectric.buildActivity(
                                        PhotoActivity.class,
                                        new Intent(app, PhotoActivity.class).putExtra("id", id))
                                .create()
                                .start();
                try {
                    assertEquals(note, c.get().note.getText().toString());
                    assertEquals(
                            code.equals("ru") ? "Сохранить в галерею" : "Save to gallery",
                            c.get().gallery.getText().toString());
                } finally {
                    c.stop().destroy();
                }
            }
            assertEquals(note, db.photo(id).note);
        }
    }
}
