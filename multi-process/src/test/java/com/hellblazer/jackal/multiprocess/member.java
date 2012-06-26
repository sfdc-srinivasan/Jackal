package com.hellblazer.jackal.multiprocess;

import com.hellblazer.jackal.testUtil.gossip.GossipNodeCfg;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class member extends GossipNodeCfg {

    @Override
    @Bean
    public int node() {
//		System.out.println("comes into member");
        return Integer.parseInt(System.getProperty(ConsoleTest.PROCESS_IDEN));
    }
}
