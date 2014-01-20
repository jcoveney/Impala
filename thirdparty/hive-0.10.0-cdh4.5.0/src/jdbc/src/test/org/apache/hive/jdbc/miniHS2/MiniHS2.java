package org.apache.hive.jdbc.miniHS2;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.HiveMetaStore;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hive.service.Service;
import org.apache.hive.service.cli.CLIServiceClient;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.SessionHandle;
import org.apache.hive.service.cli.thrift.ThriftCLIService;
import org.apache.hive.service.cli.thrift.ThriftCLIServiceClient;
import org.apache.hive.service.server.HiveServer2;

import com.google.common.io.Files;

public class MiniHS2 extends AbstarctHiveService {
  private static final String driverName = "org.apache.hive.jdbc.HiveDriver";
  private HiveServer2 hiveServer2 = null;
  private final File baseDir;
  private static final AtomicLong hs2Counter = new AtomicLong();

  public MiniHS2(HiveConf hiveConf) throws IOException {
    super(hiveConf, "localhost", MetaStoreUtils.findFreePort());
    baseDir =  Files.createTempDir();
    setWareHouseDir("file://" + baseDir.getPath() + File.separator + "warehouse");
    String metaStoreURL =  "jdbc:derby:" + baseDir.getAbsolutePath() + File.separator + "test_metastore-" +
        hs2Counter.incrementAndGet() + ";create=true";

    System.setProperty(HiveConf.ConfVars.METASTORECONNECTURLKEY.varname, metaStoreURL);
    hiveConf.setVar(HiveConf.ConfVars.METASTORECONNECTURLKEY, metaStoreURL);
    hiveConf.setVar(ConfVars.HIVE_SERVER2_THRIFT_BIND_HOST, getHost());
    hiveConf.setIntVar(ConfVars.HIVE_SERVER2_THRIFT_PORT, getPort());
    HiveMetaStore.HMSHandler.resetDefaultDBFlag();
  }

  public void start() throws Exception {
    hiveServer2 = new HiveServer2();
    hiveServer2.init(getHiveConf());
    hiveServer2.start();
    waitForStartup();
    setStarted(true);
  }

  public void stop() {
    verifyStarted();
    hiveServer2.stop();
    setStarted(false);
    FileUtils.deleteQuietly(baseDir);
  }

  public CLIServiceClient getServiceClient() {
    verifyStarted();
    return getServiceClientInternal();
  }

  public CLIServiceClient getServiceClientInternal() {
    for (Service service : hiveServer2.getServices()) {
      if (service instanceof ThriftCLIService) {
        return new ThriftCLIServiceClient((ThriftCLIService)service);
      }
    }
    throw new IllegalStateException("HS2 not running Thrift service");
  }

  public String getJdbcURL() {
    return "jdbc:hive2://" + getHost() + ":" + getPort() + "/default";
  }

  public static String getJdbcDriverName() {
    return driverName;
  }

  private void waitForStartup() throws Exception {
    int waitTime = 0;
    long startupTimeout = 1000L * 1000000000L;
    CLIServiceClient hs2Client = getServiceClientInternal();
    SessionHandle sessionHandle = null;
    do {
      Thread.sleep(500L);
      waitTime += 500L;
      if (waitTime > startupTimeout) {
        throw new TimeoutException("Couldn't access new HiveServer: " + getJdbcURL());
      }
      try {
        sessionHandle = hs2Client.openSession("foo", "bar");
      } catch (HiveSQLException e) {
        // service not started yet
        continue;
      }
      hs2Client.closeSession(sessionHandle);
      break;
    } while (true);
  }

}