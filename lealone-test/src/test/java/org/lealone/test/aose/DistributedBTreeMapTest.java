/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.test.aose;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.Test;
import org.lealone.db.value.ValueNull;
import org.lealone.storage.CursorParameters;
import org.lealone.storage.StorageMapCursor;
import org.lealone.storage.aose.btree.BTreeMap;
import org.lealone.storage.aose.btree.page.Page;
import org.lealone.storage.aose.btree.page.PageReference;
import org.lealone.storage.page.PageKey;
import org.lealone.test.TestBase.TodoTest;

public class DistributedBTreeMapTest extends AoseTestBase implements TodoTest {

    private String storagePath;

    @Test
    public void run() {
        init();
        testGetNodeToKeyMap();
        testRemotePage();
        // testLeafPageRemove(); //TODO 有bug
    }

    @Override
    protected void init() {
        super.init();
        storagePath = storage.getStoragePath();
    }

    private BTreeMap<Integer, String> openDistributedBTreeMap(String name) {
        return openDistributedBTreeMap(name, null);
    }

    private BTreeMap<Integer, String> openDistributedBTreeMap(String name, Map<String, String> parameters) {
        return storage.openBTreeMap(name, null, null, parameters);
    }

    void testGetNodeToKeyMap() {
        BTreeMap<Integer, String> map = openDistributedBTreeMap("testGetNodeToKeyMap");
        map.clear();
        testGetNodeToKeyMap(map); // 测试空map

        map.clear();
        int count = 10;
        for (int i = 1; i <= count; i++) {
            map.put(i, "value" + i);
        }
        testGetNodeToKeyMap(map); // 测试只有一个root leaf page的map

        map.clear();
        count = 6000;
        for (int i = 1; i <= count; i++) {
            map.put(i, "value" + i);
        }
        testGetNodeToKeyMap(map); // 测试有root node page的map

        map.clear();
        ArrayList<PageKey> pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(1, 9, pageKeys);
        assertEquals(1, pageKeys.size());
        assertTrue(pageKeys.get(0).key == ValueNull.INSTANCE);

        map.put(1, "value" + 1);

        pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(1, 9, pageKeys);
        assertEquals(1, pageKeys.size());
        assertTrue(pageKeys.get(0).key.equals(1));

        for (int i = 1; i <= 40; i += 2) {
            map.put(i, "value" + i);
        }

        pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(1, 9, pageKeys);
        assertEquals(1, pageKeys.size());

        pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(15, 40, pageKeys);
        assertEquals(1, pageKeys.size());

        pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(15, null, pageKeys);
        assertEquals(1, pageKeys.size());

        // map.close();
    }

    void testGetNodeToKeyMap(BTreeMap<Integer, String> map) {
        Page root = map.getRootPage();
        Random random = new Random();
        String[] ids = { "a", "b", "c", "d", "e", "f" };
        injectReplicationHostIds(root, random, ids);
        Integer from = 3; // 5900;
        Integer to = 5999;
        HashSet<PageKey> pageKeySet = new HashSet<>();
        Map<List<String>, List<PageKey>> nodeToPageKeyMap = map.getNodeToPageKeyMap(from, to);
        // System.out.println(nodeToPageKeyMap);
        for (List<PageKey> pageKeys : nodeToPageKeyMap.values()) {
            for (PageKey pk : pageKeys) {
                if (!pageKeySet.add(pk)) {
                    // System.out.println("PageKey: " + pk);
                    fail("PageKey: " + pk);
                }
            }
        }

        int count = 0;
        for (List<PageKey> pageKeys : nodeToPageKeyMap.values()) {
            // System.out.println("pageKeys: " + pageKeys);
            StorageMapCursor<Integer, String> cursor = map.cursor(CursorParameters.create(from, pageKeys));
            while (cursor.hasNext()) {
                count++;
                cursor.next();
                // System.out.println(cursor.getKey());
            }
        }
        System.out.println("count: " + count + ", to-from: " + (to - from + 1));
    }

    void injectReplicationHostIds(Page page, Random random, String[] ids) {
        if (page.isLeaf()) {
            injectReplicationHostIds(null, page, random, ids);
            return;
        }
        for (PageReference pf : page.getChildren()) {
            if (pf.getPage().isNode())
                injectReplicationHostIds(pf.getPage(), random, ids);
            else {
                injectReplicationHostIds(pf, page, random, ids);
            }
        }
    }

    void injectReplicationHostIds(PageReference pf, Page page, Random random, String[] ids) {
        int needNodes = 3;
        ArrayList<String> replicationHostIds = new ArrayList<>(needNodes);
        int totalNodes = ids.length;
        Set<Integer> indexSet = new HashSet<>(needNodes);
        while (true) {
            int i = random.nextInt(totalNodes);
            indexSet.add(i);
            if (indexSet.size() == needNodes)
                break;
        }
        for (int i : indexSet) {
            replicationHostIds.add(ids[i]);
        }
        page.setReplicationHostIds(replicationHostIds);
        if (pf != null)
            pf.setReplicationHostIds(replicationHostIds);
    }

    void testRemotePage() {
        String mapName = "RemotePageTest";
        String dir = storagePath + File.separator + mapName;
        deleteFileRecursive(dir);

        Map<String, String> parameters = new HashMap<>();
        parameters.put("isShardingMode", "true");
        try {
            openDistributedBTreeMap(mapName, parameters);
            fail();
        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
        parameters.put("initReplicationNodes", "127.0.0.1:1111&127.0.0.1:2222");
        BTreeMap<Integer, String> map = openDistributedBTreeMap(mapName, parameters);
        assertTrue(map.getRootPage().isRemote());
        assertEquals(2, map.getRootPage().getReplicationHostIds().size());

        try {
            assertNull(map.put(1, "abc"));
            // fail();
        } catch (Exception e) {
            System.out.println("RemotePage put: " + e.getMessage());
        }

        try {
            map.get(1);
            fail();
        } catch (Exception e) {
            System.out.println("RemotePage get: " + e.getMessage());
        }
    }

    void testLeafPageRemove() {
        BTreeMap<Integer, String> map = openDistributedBTreeMap("testLeafPageRemove");
        map.clear();

        for (int i = 1; i <= 40; i += 2) {
            map.put(i, "value" + i);
        }

        for (int i = 1; i <= 40; i += 2) {
            map.remove(i);
        }

        for (int i = 1; i <= 40; i += 2) {
            map.put(i, "value" + i);
        }
        // 上面put的数据得到一个node page加两个leaf page
        ArrayList<PageKey> pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(1, 50, pageKeys);
        assertEquals(2, pageKeys.size());

        PageKey pk = pageKeys.get(0);
        map.removeLeafPage(pk);

        pk = pageKeys.get(1);
        map.removeLeafPage(pk);

        assertTrue(map.getRootPage().isEmpty());

        map.clear();

        // 测试多层node page
        for (int i = 1; i <= 500; i += 2) {
            map.put(i, "value" + i);
        }
        pageKeys = new ArrayList<>();
        map.getNodeToPageKeyMap(1, 500, pageKeys);
        for (PageKey pageKey : pageKeys)
            map.removeLeafPage(pageKey);
        assertTrue(map.getRootPage().isEmpty());
    }
}
