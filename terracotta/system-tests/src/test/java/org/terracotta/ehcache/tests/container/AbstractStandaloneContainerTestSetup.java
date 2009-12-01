/*
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 */
package org.terracotta.ehcache.tests.container;

import net.sf.ehcache.Ehcache;
import net.sf.ehcache.terracotta.StandaloneTerracottaStoreFactory;

import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.StaticLoggerBinder;

import com.tc.test.server.appserver.deployment.AbstractStandaloneTwoServerDeploymentTest;
import com.tc.test.server.appserver.deployment.DeploymentBuilder;
import com.tc.test.server.appserver.deployment.TempDirectoryUtil;
import com.tc.test.server.appserver.deployment.AbstractStandaloneTwoServerDeploymentTest.StandaloneTwoServerTestSetup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class AbstractStandaloneContainerTestSetup extends StandaloneTwoServerTestSetup {

  private final String ehcacheConfigTemplate;

  public AbstractStandaloneContainerTestSetup(Class<? extends AbstractStandaloneTwoServerDeploymentTest> testClass,
                                              String ehcacheConfigTemplate, String context) {
    super(testClass, context);
    this.ehcacheConfigTemplate = ehcacheConfigTemplate;
  }

  protected void configureWar(DeploymentBuilder builder) {
    addCommonJars(builder);
    builder.addFileAsResource(getTempEhcacheConfigFile(), "WEB-INF/classes/");
  }

  private void addCommonJars(DeploymentBuilder builder) {
    builder.addDirectoryOrJARContainingClass(Assert.class); // junit
    builder.addDirectoryOrJARContainingClass(Ehcache.class); // ehcache
    builder.addDirectoryOrJARContainingClass(StandaloneTerracottaStoreFactory.class); // ehcache-terracotta
    builder.addDirectoryOrJARContainingClass(LoggerFactory.class); // slf4j-api
    builder.addDirectoryOrJARContainingClass(StaticLoggerBinder.class); // slf4j-simple
  }

  private File getTempEhcacheConfigFile() {
    try {
      File ehcacheConfigFile = writeDefaultConfigFile(TempDirectoryUtil.getTempDirectory(this.getClass()), getServerManager()
          .getServerTcConfig().getDsoPort());
      System.out.println("Wrote temp config file at: " + ehcacheConfigFile.getAbsolutePath());
      return ehcacheConfigFile;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private File writeDefaultConfigFile(File configFileLocation, int port) throws IOException {
    return writeConfigFile(configFileLocation, "ehcache.xml", port);
  }

  private File writeConfigFile(File configFileLocation, String fileName, int port) throws IOException {
    InputStream in = null;
    FileOutputStream out = null;

    try {
      in = getClass().getClassLoader().getResourceAsStream(ehcacheConfigTemplate);
      File rv = new File(configFileLocation, fileName);
      out = new FileOutputStream(rv);
      String template = IOUtils.toString(in);
      String config = template.replace("PORT", String.valueOf(port));
      out.write(config.getBytes());
      return rv;
    } finally {
      IOUtils.closeQuietly(in);
      IOUtils.closeQuietly(out);
    }
  }
}
