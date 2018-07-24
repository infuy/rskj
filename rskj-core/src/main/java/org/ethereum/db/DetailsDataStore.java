/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.db;

import co.rsk.config.RskSystemProperties;
import co.rsk.core.RskAddress;
import co.rsk.crypto.Keccak256;
import co.rsk.db.ContractDetailsImpl;
import org.ethereum.crypto.Keccak256Helper;
import org.ethereum.datasource.LevelDbDataSource;
import org.iq80.leveldb.DBIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.String.format;
import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.iq80.leveldb.impl.Iq80DBFactory.asString;

/**
 * A store for contract details.
 */
public class DetailsDataStore {

    private static final Logger gLogger = LoggerFactory.getLogger("general");

    private final Map<RskAddress, ContractDetails> cache = new ConcurrentHashMap<>();
    private final Set<RskAddress> removes = new HashSet<>();
    private final Map<Keccak256, byte[]> codeCache = new ConcurrentHashMap<>();

    private final RskSystemProperties config;
    private final DatabaseImpl db;
    private final DatabaseImpl codedb;

    private boolean versionSet = false;

    public DetailsDataStore(RskSystemProperties config, DatabaseImpl db, DatabaseImpl codedb) {
        this.config = config;
        this.db = db;
        this.codedb = codedb;
    }

    public synchronized ContractDetails get(RskAddress addr) {
        ContractDetails details = cache.get(addr);

        if (details == null) {

            if (removes.contains(addr)) {
                return null;
            }
            byte[] data = db.get(addr.getBytes());
            if (data == null) {
                return null;
            }

            details = createContractDetails(data);
            cache.put(addr, details);

            float out = ((float) data.length) / 1048576;
            if (out > 10) {
                String sizeFmt = format("%02.2f", out);
                gLogger.debug("loaded: address: {}, size: {}MB", addr, sizeFmt);
            }
        }

        return details;
    }

    public byte[] getCode(Keccak256 hash) {
        byte[] code = codeCache.get(hash);
        if (code != null) {
            return code;
        }
        code = codedb.get(hash.getBytes());
        if (code != null) {
            codeCache.put(hash, code);
        }
        return code;
    }

    protected ContractDetails createContractDetails(byte[] data) {
        return new ContractDetailsImpl(config, data);
    }

    public synchronized void update(RskAddress addr, ContractDetails contractDetails) {
        contractDetails.setAddress(addr.getBytes());
        cache.put(addr, contractDetails);
        removes.remove(addr);
    }

    public synchronized void remove(RskAddress addr) {
        cache.remove(addr);
        removes.add(addr);
    }

    public synchronized void newCode(Keccak256 hash, byte[] code) {
        codeCache.put(hash, code);
    }

    public synchronized void flush() {
        long keys = cache.size();
        keys += codeCache.size();

        long start = System.nanoTime();
        long totalSize = flushInternal();
        long finish = System.nanoTime();

        float flushSize = (float) totalSize / 1_048_576;
        float flushTime = (float) (finish - start) / 1_000_000;
        gLogger.trace(format("Flush details and code in: %02.2f ms, %d keys, %02.2fMB", flushTime, keys, flushSize));

    }

    private long flushInternal() {
        long totalSize = 0;

        Map<byte[], byte[]> batch = new HashMap<>();
        Map<byte[], byte[]> codeBatch = new HashMap<>();
        for (Map.Entry<RskAddress, ContractDetails> entry : cache.entrySet()) {
            ContractDetails details = entry.getValue();
            details.syncStorage();

            byte[] key = entry.getKey().getBytes();
            byte[] value = details.getEncoded();

            batch.put(key, value);
            totalSize += value.length;
        }

        for (Map.Entry<Keccak256, byte[]> entry : codeCache.entrySet()) {
            codeBatch.put(entry.getKey().getBytes(), entry.getValue());
            totalSize += entry.getValue().length;
        }

        db.getDb().updateBatch(batch);
        codedb.getDb().updateBatch(codeBatch);
        for (RskAddress key : removes) {
            db.delete(key.getBytes());
        }

        cache.clear();
        codeCache.clear();
        removes.clear();

        return totalSize;
    }


    public synchronized Set<RskAddress> keys() {
        Set<RskAddress> keys = new HashSet<>();
        keys.addAll(cache.keySet());
        keys.addAll(db.dumpKeys(RskAddress::new));

        return keys;
    }

    public boolean checkAndMigrateDB(LevelDbDataSource dst) throws IOException {

        DBIterator iterator = ((LevelDbDataSource)db.getDb()).getLevelDb().iterator();
        try {
            int count = 0;
            gLogger.info("Starting code db migration. This process may take a few minutes.");
            for(iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
                byte[] key = iterator.peekNext().getKey();
                byte[] value = iterator.peekNext().getValue();
                if (key.length > 20) {
                    //ABORT
                    gLogger.warn("Unexpected key size on details db k:{} v:{}", Hex.toHexString(key), Hex.toHexString(value));
                    throw new IllegalStateException("Unexpected key size on details db k:" + Hex.toHexString(key) + "v:" + Hex.toHexString(value));
                }
                byte[] code = extractCode(value);
                code = code != null ? code : EMPTY_BYTE_ARRAY;
                ContractDetailsImpl details = createFromOldData(value);

                dst.put(key, details.getEncoded());
                // Hash was saved on another db
                // I wanted to avoid calculating this hash here, but ideally this code will be deleted soon
                Keccak256 hash = new Keccak256(Keccak256Helper.keccak256(code));
                newCode(hash, code);
            }
            gLogger.info("Code db migration ended succesfully");
        } finally {
            iterator.close();
        }
        return true;
    }

    protected byte[] extractCode(byte[] data) {
        return ContractDetailsImpl.extractCode(data);

    }

    protected ContractDetailsImpl createFromOldData(byte[] data) {
        return ContractDetailsImpl.fromOldData(config, data);
    }
}
