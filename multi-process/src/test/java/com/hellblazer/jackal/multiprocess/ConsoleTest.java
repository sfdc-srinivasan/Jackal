package com.hellblazer.jackal.multiprocess;

import com.hellblazer.jackal.multiprocess.controller.gui.TestControllerConfig;
import com.hellblazer.jackal.testUtil.gossip.GossipTestCfg;
import com.hellblazer.process.JavaProcess;
import com.hellblazer.process.Utils;
import com.hellblazer.process.impl.JavaProcessImpl;
import com.hellblazer.process.impl.ManagedProcessFactoryImpl;
import org.junit.Before;
import org.junit.Test;
import org.smartfrog.services.anubis.partition.test.controller.gui.GraphicController;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import java.io.File;

public class ConsoleTest extends ProcessTest {

    protected static final String TEST_DIR = "test-dirs/java-process-test";
    MBeanServerConnection connection;
    JMXConnector connector;
    ManagedProcessFactoryImpl processFactory = new ManagedProcessFactoryImpl();
    protected File testDir;

    final int numberOfProcesses = 20;

    static {
        GossipTestCfg.setTestPorts(24730, 24750);
    }

    private AnnotationConfigApplicationContext controllerContext;
    private GraphicController controller;
    static final String PROCESS_IDEN = "process.iden";

    @Before
    public void setUp() {
        System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
        System.setProperty("sun.net.client.defaultReadTimeout", "10000");
        System.setProperty("javax.net.debug", "all");
        Utils.initializeDirectory(TEST_DIR);
        testDir = new File(TEST_DIR);
    }

/*    protected Class<?>[] getConfigs() {
        return new Class<?>[] { member.class, member.class, member.class,
                member.class, member.class, member.class, member.class,
                member.class, member.class, member.class, member.class,
                member.class, member.class, member.class, member.class,
                member.class, member.class, member.class, member.class,
                member.class };
    }*/


    protected Class<?>[] getConfigs() {
        return new Class<?>[]{member.class, member.class, member.class};
    }

    protected Class<?> getControllerConfig() {
        return TestControllerConfig.class;
    }

    @Test
    public void testMultiProcesses() throws Exception {
        controllerContext = new AnnotationConfigApplicationContext(
                getControllerConfig());
        controller = controllerContext.getBean(GraphicController.class);
        assertNotNull(controller);
        String[] args = new String[20];
        String className = "";
        int i = 0;
        for (Class<?> config : getConfigs()) {
            System.out.println("class:" + config.toString());
            className = config.toString().replace("class ", "");

            JavaProcess process = new JavaProcessImpl(processFactory.create());
            process.setVmOptions(new String[]{"-cp",
                    System.getProperty("java.class.path"),
                    "-D" + PROCESS_IDEN + "=" + i});
            process.setArguments(new String[]{className});
            process.setJavaClass(CheckBasic.class.getCanonicalName());
            assertNull("No jar file set", process.getJarFile());
            process.setDirectory(testDir);
            process.setJavaExecutable(javaBin);
            process.start();
            i++;

        }
        //PS: Here is how one kills any stray java processes that may linger after this test
        //ps -ef | grep java | grep member | sed -e's/  */ /'g | cut -d' ' -f2 | xargs kill
        //PPS: explicitly killing will no longer be necessary after I kill them during tearDoen
        System.out.println("waiting again for 50 seconds ...");

        Thread.sleep(50000);

    }

}

