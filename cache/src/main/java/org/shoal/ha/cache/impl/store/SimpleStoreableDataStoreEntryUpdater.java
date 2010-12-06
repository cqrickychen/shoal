package org.shoal.ha.cache.impl.store;

import org.glassfish.ha.store.util.SimpleMetadata;
import org.shoal.adapter.store.commands.LoadResponseCommand;
import org.shoal.adapter.store.commands.SaveCommand;
import org.shoal.ha.cache.api.*;

/**
 * @author Mahesh Kannan
 * 
 */
public class SimpleStoreableDataStoreEntryUpdater<K, V extends SimpleMetadata>
    extends DataStoreEntryUpdater<K, V> {

    @Override
    public SaveCommand<K, V> createSaveCommand(DataStoreEntry<K, V> entry, K k, V v) {
        SaveCommand<K, V> cmd = new SaveCommand<K, V>(k, v, v._storeable_getVersion(),
                v._storeable_getLastAccessTime(), v._storeable_getMaxIdleTime());
        super.updateMetaInfoInDataStoreEntry(entry, cmd);
        entry.setIsReplicaNode(false);
        return cmd;
    }

    @Override
    public V extractVFrom(LoadResponseCommand<K, V> cmd)
        throws DataStoreException {
        return (V) new SimpleMetadata(cmd.getVersion(),
                    System.currentTimeMillis(), 600000, cmd.getRawV());
    }

    @Override
    public LoadResponseCommand<K, V> createLoadResponseCommand(DataStoreEntry<K, V> entry, K k, long minVersion) {
        LoadResponseCommand<K, V> cmd = null;
        if (entry != null && entry.isReplicaNode() && entry.getVersion() >= minVersion) {
//            System.out.println("SimpleStoreableDataStoreEntryUpdater.createLoadResp "
//                + " entry.version " + entry.getVersion() + ">= " + minVersion
//                + "; rawV.length = " + entry.getRawV());
            cmd = new LoadResponseCommand<K, V>(k, entry.getVersion(), entry.getRawV());
        } else {
            String entryMsg = (entry == null) ? "NULL ENTRY"
                    : (entry.getVersion() + " >= " + minVersion);
//            System.out.println("SimpleStoreableDataStoreEntryUpdater.createLoadResp "
//                + entryMsg
//                + "; rawV.length = " + (entry == null ? " null " : "" + entry.getRawV()));
            cmd = new LoadResponseCommand<K, V>(k, Long.MIN_VALUE, null);
        }
        return cmd;
    }

    @Override
    public void executeSave(DataStoreEntry<K, V> entry, SaveCommand<K, V> cmd) {
        if (entry != null && entry.getVersion() < cmd.getVersion()) {
            entry.setIsReplicaNode(true);
            super.updateMetaInfoInDataStoreEntry(entry, cmd);
            entry.setRawV(cmd.getRawV());
            super.printEntryInfo("SimpleStoreableDataStoreEntryUpdater:Updated", entry, cmd.getKey());
        }
    }

    @Override
    public V getV(DataStoreEntry<K, V> entry)
        throws DataStoreException {
        V v = entry == null ? null : entry.getV();
        if (entry != null && v == null && entry.getRawV() != null) {
            SimpleMetadata ssm = new SimpleMetadata(entry.getVersion(),
                    entry.getLastAccessedAt(), entry.getMaxIdleTime(), entry.getRawV());
            v = (V) ssm;
        }

        return v;
    }


    @Override
    public byte[] getState(V v)
        throws DataStoreException {
        return v.getState();
    }

}