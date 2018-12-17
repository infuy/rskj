package co.rsk.blocks;

import co.rsk.config.RskSystemProperties;
import org.ethereum.core.Block;
import org.ethereum.datasource.KeyValueDataSource;
import org.ethereum.datasource.LevelDbDataSource;
import org.ethereum.db.IndexedBlockStore;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.util.List;
import java.util.Map;

import static org.ethereum.db.IndexedBlockStore.BLOCK_INFO_SERIALIZER;

/**
 * Created by Sergio Demian Lerner on 12/17/2018.
 */
public class BlockstoreBlockPlayer implements BlockPlayer, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger("blockplayer");
    private final RskSystemProperties config;
    IndexedBlockStore blockStore;
    String databaseDir;
    long blockNumber =1;
    DB indexDB;
    KeyValueDataSource blocksDB;

    public BlockstoreBlockPlayer(RskSystemProperties config, String filename) {
        this.config = config;
        this.databaseDir = filename;
        //
        blockStore = createBlockstore();
        if (blockStore ==null) {
            logger.error("Cannot open database to replay blocks fromb: ", filename);
        }

    }



    protected IndexedBlockStore createBlockstore() {
        // filePath : must not include the "/blocks/" subdirectory.
        File blockIndexDirectory = new File(databaseDir+ "/blocks/");
        File dbFile = new File(blockIndexDirectory, "index");
        if (!blockIndexDirectory.exists()) {
            return null;
        }

        indexDB = DBMaker.fileDB(dbFile)
                .closeOnJvmShutdown()
                .make();

        Map<Long, List<IndexedBlockStore.BlockInfo>> indexMap = indexDB.hashMapCreate("index")
                .keySerializer(Serializer.LONG)
                .valueSerializer(BLOCK_INFO_SERIALIZER)
                .counterEnable()
                .makeOrGet();

        blocksDB = new LevelDbDataSource("blocks", databaseDir);
        blocksDB.init();

        return new IndexedBlockStore(indexMap, blocksDB, indexDB);
    }

    public Block readBlock() {
        Block result = blockStore.getChainBlockByNumber(blockNumber);
        if (result==null)
            return result;
        blockNumber++;
        return result;

    }

    @Override
    public void close() throws Exception {
        if (indexDB!=null)
            indexDB.close();
        if (blocksDB!=null)
            blocksDB.close();
    }
}