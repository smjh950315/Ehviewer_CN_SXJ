package com.hippo.ehviewer;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;

import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DaoMaster;
import com.hippo.ehviewer.dao.DaoSession;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.dao.DownloadsDao;

import org.greenrobot.greendao.database.StandardDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, application = Application.class, sdk = 28)
public class DownloadCategoryQueryTest {
    private SQLiteDatabase database;
    private DownloadsDao downloads;
    private Field sessionField;
    private Object previousSession;

    @Before
    public void setUp() throws Exception {
        database = SQLiteDatabase.create(null);
        StandardDatabase db = new StandardDatabase(database);
        DownloadsDao.createTable(db, false);
        DaoSession session = new DaoMaster(db).newSession();
        downloads = session.getDownloadsDao();
        sessionField = EhDB.class.getDeclaredField("sDaoSession");
        sessionField.setAccessible(true);
        previousSession = sessionField.get(null);
        sessionField.set(null, session);
    }

    @After
    public void tearDown() throws Exception {
        sessionField.set(null, previousSession);
        database.close();
    }

    @Test
    public void categoryQueryUsesDatabaseAndKeepsCurrentLabel() {
        insert(1, EhConfig.MANGA, null, null);
        insert(2, EhConfig.DOUJINSHI, null, null);
        insert(3, EhConfig.MANGA, "favorites", null);
        List<DownloadInfo> initialList = EhDB.queryDownloadInfoByCategory(
                null, EhConfig.MANGA, false);
        assertIds(initialList, 1L);

        // The original list has not changed, but the next query must see this new row.
        insert(4, EhConfig.MANGA, null, null);
        assertIds(initialList, 1L);
        assertIds(EhDB.queryDownloadInfoByCategory(null, EhConfig.MANGA, false), 4L, 1L);
        assertIds(EhDB.queryDownloadInfoByCategory("favorites", EhConfig.MANGA, false), 3L);
    }

    @Test
    public void allIncludesEveryCategoryOnlyInCurrentLabel() {
        insert(1, EhConfig.MANGA, null, null);
        insert(2, EhConfig.DOUJINSHI, null, null);
        insert(3, EhUtils.UNKNOWN, null, null);
        insert(4, EhConfig.MANGA, "favorites", null);
        assertIds(EhDB.queryDownloadInfoByCategory(null, EhUtils.ALL_CATEGORY, false),
                3L, 2L, 1L);
    }

    @Test
    public void unknownMatchesOnlyUnknownCategory() {
        insert(1, EhConfig.MANGA, null, null);
        insert(2, EhUtils.UNKNOWN, null, null);
        assertIds(EhDB.queryDownloadInfoByCategory(null, EhUtils.UNKNOWN, false), 2L);
    }

    @Test
    public void localImportsRequireNonemptyUriInCurrentLabel() {
        insert(1, EhConfig.MANGA, null, null);
        insert(2, EhConfig.MANGA, null, "");
        insert(3, EhConfig.MISC, null, "content://archives/3");
        insert(4, EhConfig.MISC, "favorites", "content://archives/4");
        assertIds(EhDB.queryDownloadInfoByCategory(null, EhUtils.ALL_CATEGORY, true), 3L);
    }

    @Test
    public void filteringDoesNotResetActiveDownloadState() {
        DownloadInfo info = insert(1, EhConfig.MANGA, null, null);
        info.state = DownloadInfo.STATE_DOWNLOAD;
        downloads.update(info);
        List<DownloadInfo> result = EhDB.queryDownloadInfoByCategory(
                null, EhConfig.MANGA, false);
        assertEquals(DownloadInfo.STATE_DOWNLOAD, result.get(0).state);
        assertEquals(DownloadInfo.STATE_DOWNLOAD, info.state);
    }

    private DownloadInfo insert(long gid, int category, String label, String archiveUri) {
        DownloadInfo info = new DownloadInfo(gid);
        info.category = category;
        info.label = label;
        info.archiveUri = archiveUri;
        info.time = gid;
        downloads.insert(info);
        return info;
    }

    private static void assertIds(List<DownloadInfo> list, Long... expected) {
        assertEquals(Arrays.asList(expected),
                list.stream().map(info -> info.gid).collect(Collectors.toList()));
    }
}
