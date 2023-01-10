/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.messaging.lifecycle;

import static com.ibm.websphere.ras.Tr.debug;
import static com.ibm.websphere.ras.TraceComponent.isAnyTracingEnabled;
import static java.util.Collections.unmodifiableSet;
import static org.osgi.service.component.annotations.ConfigurationPolicy.IGNORE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MULTIPLE;
import static org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.ws.ffdc.FFDCFilter;
import com.ibm.wsspi.logging.Introspector;

/**
 * This component tracks {@link Singleton} services as they arrive and depart.
 * If SingletonsReady does not become available, SingletonMonitor will show the progress so far.
 * It also dumps the current state of expected and realised {@link Singleton}s when a server dump is taken.
 * 
 * @see SingletonsReady
 */
@Component(
        immediate = true, 
        configurationPolicy = IGNORE,
        property = {
                "osgi.command.scope=sib",
                "osgi.command.function=singletons",
                "service.vendor=IBM"
        })
public class SingletonMonitor implements Introspector, ConfigurationListener {
    public static final TraceComponent tc = Tr.register(SingletonMonitor.class);
    private static final AtomicInteger counter = new AtomicInteger(0);
    private final int version = counter.incrementAndGet();
    private final Set<String> realizedSingletons = new TreeSet<>();
    private final ConfigurationAdmin configAdmin;
    private volatile Set<String> declaredSingletons;

    @Activate
    public SingletonMonitor(@Reference(name="configAdmin") ConfigurationAdmin configAdmin) {
        if (isAnyTracingEnabled() && tc.isEntryEnabled()) debug(this, tc, "SingletonMonitor constructed");

        this.configAdmin = configAdmin;
        this.declaredSingletons = unmodifiableSet(findDeclaredSingletons(configAdmin));
        if (isAnyTracingEnabled() && tc.isDebugEnabled()) debug(this, tc, "Declared singletons: " + declaredSingletons);
    }

    private static Set<String> findDeclaredSingletons(ConfigurationAdmin configAdmin) {
        Configuration[] configs;
        try {
            configs = configAdmin.listConfigurations("(service.factoryPid=com.ibm.ws.messaging.lifecycle.SingletonAgent)");
            if (isAnyTracingEnabled() && tc.isDebugEnabled()) debug(tc, "Singleton configs: ", Arrays.toString(configs));
        } catch (IOException | InvalidSyntaxException e) {
            FFDCFilter.processException(e, "com.ibm.ws.messaging.lifecycle.SingletonMonitor.findDeclaredSingletons", "list configs");
            throw new LifecycleError("Could not list declared singletons", e);
        }
        return Stream.of(configs)
                .map(Configuration::getProperties)
                .map(dict -> dict.get("id"))
                .map(String.class::cast)
                .collect(TreeSet::new, Set::add, Set::addAll);
    }

    @Override
    public void configurationEvent(ConfigurationEvent cfgEvent) {
        Set<String> oldSet = declaredSingletons;
        Set<String> newSet = findDeclaredSingletons(configAdmin);
        if (newSet.equals(oldSet)) return;
        // Now we know there are some changes.
        this.declaredSingletons = newSet;
        if (isAnyTracingEnabled() && tc.isDebugEnabled()) {
            // calculate the diffs
            Set<String> removed = new TreeSet<>(oldSet), added = new TreeSet<>(newSet), unmodified = new TreeSet<>(oldSet);
            removed.removeAll(newSet);
            added.removeAll(oldSet);
            unmodified.removeAll(removed);
            // trace the diffs
            if (unmodified.size() > 0) debug(this, tc, "Singleton declarations unmodified:", unmodified);
            if (removed.size() > 0) debug(this, tc, "Singleton declarations removed:", removed);
            if (added.size() > 0) debug(this, tc, "Singleton declarations added:", added);
        }
    }

    @Deactivate
    public void deactivate() {
        if (isAnyTracingEnabled() && tc.isDebugEnabled()) debug(tc, this + " deactivate");
    }

    @Reference(cardinality = MULTIPLE, policy = DYNAMIC, policyOption = GREEDY)
    synchronized void addRealizedSingleton(SingletonAgent agent) {
	realizedSingletons.add(agent.getSingletonType());
        if (isAnyTracingEnabled() && tc.isDebugEnabled()) debug(this, tc, String.format("Realized Singleton added: %s%nDeclared: %s%n:  Realized: %s", agent.getSingletonType(), declaredSingletons, realizedSingletons));
    }

    synchronized void removeRealizedSingleton(SingletonAgent agent) {
        realizedSingletons.remove(agent.getSingletonType());
        if (isAnyTracingEnabled() && tc.isDebugEnabled()) debug(this, tc, String.format("Realized Singleton removed: %s%nDeclared: %s%n  Realized: %s", agent.getSingletonType(), declaredSingletons, realizedSingletons));
    }

    @Override
    public String toString() {
        return SingletonMonitor.class.getName() + "#" + version;
    }

    @Override
    public String getIntrospectorName() {
        return "Messaging" + getClass().getSimpleName();
    }

    @Override
    public String getIntrospectorDescription() {
        return String.format("List the declared (D) and the realized (R) messaging singletons.%n" +
                             "Messaging cannot start until all the declared singletons become available (i.e. are realized).");
    }

    @Override
    public void introspect(PrintWriter out) throws Exception {
        Stream.concat(declaredSingletons.stream(), realizedSingletons.stream())
            .sorted()
            .distinct()
            .sequential()
            .peek(s -> out.print('['))
            .peek(s -> out.print(declaredSingletons.contains(s) ? 'D' : ' '))
            .peek(s -> out.print(realizedSingletons.contains(s) ? 'R' : ' '))
            .peek(s -> out.print("] "))
            .forEach(out::println);
    }
    
    public void singletons() throws Exception {
        System.out.println(getIntrospectorName());
        System.out.println(getIntrospectorDescription());
        try (PrintWriter pw = new PrintWriter(System.out)) {
            introspect(pw);
        }
    }
}
