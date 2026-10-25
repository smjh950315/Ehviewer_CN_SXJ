package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryTagGroup;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DaoSession;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadLabelDao;
import com.hippo.ehviewer.dao.DownloadsDao;
import com.hippo.ehviewer.dao.GalleryTagsDao;
import com.hippo.ehviewer.sync.DownloadListInfosExecutor;
import com.hippo.ehviewer.sync.GalleryDetailTagsSyncTask;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 28)
public class DownloadMetadataPersistenceTest {
    private SQLiteDatabase database;
    private DaoSession session;
    private Field sessionField;
    private Object previousSession;
    private DownloadManager manager;

    @Before
    public void setUp() throws Exception {
        database = SQLiteDatabase.create(null);
        StandardDatabase db = new StandardDatabase(database);
        DownloadsDao.createTable(db, false);
        DownloadLabelDao.createTable(db, false);
        GalleryTagsDao.createTable(db, false);
        session = new DaoMaster(db).newSession();
        sessionField = EhDB.class.getDeclaredField("sDaoSession");
        sessionField.setAccessible(true);
        previousSession = sessionField.get(null);
        sessionField.set(null, session);

        DownloadInfo info = new DownloadInfo(123L);
        info.token = "token";
        info.title = "Old title";
        info.state = DownloadInfo.STATE_FINISH;
        info.time = 42L;
        session.getDownloadsDao().insert(info);
        manager = new DownloadManager(RuntimeEnvironment.application);
    }

    @After
    public void tearDown() throws Exception {
        sessionField.set(null, previousSession);
        database.close();
    }

    @Test
    public void savedTagsAreSearchableOfflineDespiteOldInMemoryTags() throws Exception {
        DownloadInfo info = manager.getDownloadInfo(123L);
        info.simpleTags = new String[]{"artist:old"};
        info.tgList = new ArrayList<>(Arrays.asList("artist:cached"));
        assertFalse(EhDB.inGalleryTags(info.gid));

        GalleryDetail detail = detail("fresh artist");
        new GalleryDetailTagsSyncTask(detail).save();
        manager.updateGalleryMetadata(detail);

        assertTrue(EhDB.inGalleryTags(info.gid));
        assertEquals("fresh artist", EhDB.queryGalleryTags(info.gid).artist);
        DownloadListInfosExecutor search = new DownloadListInfosExecutor(
                manager.getAllDownloadInfoList(), "artist:fresh artist");
        Method match = DownloadListInfosExecutor.class.getDeclaredMethod(
                "matchTag", String.class, DownloadInfo.class);
        match.setAccessible(true);
        assertTrue((Boolean) match.invoke(search, "artist:fresh artist", info));
        assertFalse((Boolean) match.invoke(search, "artist:cached", info));
        assertFalse((Boolean) match.invoke(search, "artist:old", info));
    }

    @Test
    public void refreshingAnExistingGidReplacesTagsAndPreservesDownloadState() {
        new GalleryDetailTagsSyncTask(detail("old artist")).save();
        DownloadInfo info = manager.getDownloadInfo(123L);
        info.state = DownloadInfo.STATE_DOWNLOAD;
        info.finished = 7;
        info.total = 10;

        GalleryDetail detail = detail("new artist");
        new GalleryDetailTagsSyncTask(detail).save();
        manager.updateGalleryMetadata(detail);

        assertEquals(1L, session.getGalleryTagsDao().count());
        assertEquals("new artist", EhDB.queryGalleryTags(info.gid).artist);
        assertEquals(DownloadInfo.STATE_DOWNLOAD, info.state);
        assertEquals(7, info.finished);
        assertEquals(10, info.total);
        session.getDownloadsDao().detachAll();
        DownloadInfo stored = session.getDownloadsDao().load(info.gid);
        assertEquals("Updated title", stored.title);
        assertEquals("https://example.org/cover.jpg", stored.thumb);
        assertEquals(DownloadInfo.STATE_DOWNLOAD, stored.state);
        assertEquals(42L, stored.time);
    }

    @Test
    public void lateMetadataCannotAddAnUnlistedDownload() {
        GalleryDetail detail = detail("artist");
        detail.gid = 456L;
        manager.updateGalleryMetadata(detail);
        assertNull(session.getDownloadsDao().load(detail.gid));
        assertEquals(1L, session.getDownloadsDao().count());
    }

    private GalleryDetail detail(String artist) {
        GalleryTagGroup group = new GalleryTagGroup();
        group.groupName = "artist";
        group.addTag(artist);
        GalleryDetail detail = new GalleryDetail();
        detail.gid = 123L;
        detail.title = "Updated title";
        detail.thumb = "https://example.org/cover.jpg";
        detail.tags = new GalleryTagGroup[]{group};
        return detail;
    }
}
