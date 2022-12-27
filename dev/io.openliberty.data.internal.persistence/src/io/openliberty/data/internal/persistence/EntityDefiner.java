/*******************************************************************************
 * Copyright (c) 2022 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package io.openliberty.data.internal.persistence;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.ibm.websphere.ras.Tr;
import com.ibm.websphere.ras.TraceComponent;
import com.ibm.websphere.ras.annotation.Trivial;
import com.ibm.wsspi.kernel.service.utils.FilterUtils;
import com.ibm.wsspi.persistence.DatabaseStore;
import com.ibm.wsspi.persistence.PersistenceServiceUnit;

import jakarta.data.Column;
import jakarta.data.DiscriminatorColumn;
import jakarta.data.DiscriminatorValue;
import jakarta.data.Embeddable;
import jakarta.data.Entity;
import jakarta.data.Generated;
import jakarta.data.Id;
import jakarta.data.Inheritance;
import jakarta.data.MappedSuperclass;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;

/**
 * Runs asynchronously to supply orm.xml for entities that aren't already Jakarta Persistence entities
 * and to discover information about entities.
 */
class EntityDefiner implements Runnable {
    private static final String EOLN = String.format("%n");
    private static final TraceComponent tc = Tr.register(EntityDefiner.class);

    private final String databaseId;
    private final List<Class<?>> entities;
    private final ClassLoader loader;
    private final PersistenceDataProvider provider;

    EntityDefiner(PersistenceDataProvider provider, String databaseId, ClassLoader loader, List<Class<?>> entities) {
        this.provider = provider;
        this.databaseId = databaseId;
        this.loader = loader;
        this.entities = entities;
    }

    private static String getID(Class<?> entityClass) {
        // For now, choosing "id" or any field that ends with id
        String id = null;
        String upperID = null;
        for (Field field : entityClass.getFields()) {
            if (field.getAnnotation(Id.class) != null)
                return field.getName();

            String name = field.getName().toUpperCase();
            if ("ID".equals(name))
                id = field.getName();
            else if ((id == null || id.length() != 2) && name.endsWith("ID"))
                if (upperID == null || name.compareTo(upperID) < 0) {
                    upperID = name;
                    id = field.getName();
                }
        }

        if (id == null)
            throw new IllegalArgumentException(entityClass + " lacks public field with @Id or of the form *ID");
        return id;
    }

    @Override
    @Trivial
    public void run() {
        final boolean trace = TraceComponent.isAnyTracingEnabled();
        if (trace && tc.isEntryEnabled())
            Tr.entry(this, tc, "run: define entities", entities);

        EntityManager em = null;
        try {
            BundleContext bc = FrameworkUtil.getBundle(DatabaseStore.class).getBundleContext();
            Collection<ServiceReference<DatabaseStore>> refs = bc.getServiceReferences(DatabaseStore.class,
                                                                                       FilterUtils.createPropertyFilter("id", databaseId));
            if (refs.isEmpty())
                throw new IllegalArgumentException("Not found: " + databaseId);

            ServiceReference<DatabaseStore> ref = refs.iterator().next();
            String tablePrefix = (String) ref.getProperty("tablePrefix");

            if (trace && tc.isDebugEnabled())
                Tr.debug(this, tc, databaseId + " databaseStore reference", ref);

            // Classes explicitly annotated with JPA @Entity:
            ArrayList<String> entityClassNames = new ArrayList<>(entities.size());

            // XML to make all other classes into JPA entities:
            ArrayList<String> entityClassInfo = new ArrayList<>(entities.size());

            List<Class<?>> embeddableTypes = new ArrayList<>();

            Map<Class<?>, String> keyAttributeNames = new HashMap<>();

            for (Class<?> c : entities) {
                String keyAttributeName = getID(c);
                keyAttributeNames.put(c, keyAttributeName);

                if (c.getAnnotation(jakarta.persistence.Entity.class) == null) {
                    Entity entity = c.getAnnotation(Entity.class);
                    StringBuilder xml = new StringBuilder(500).append(" <entity class=\"" + c.getName() + "\">").append(EOLN);

                    if (c.getAnnotation(Inheritance.class) == null) {
                        String tableName = tablePrefix + (entity == null || entity.value().length() == 0 ? c.getSimpleName() : entity.value());
                        xml.append("  <table name=\"" + tableName + "\"/>").append(EOLN);
                    } else {
                        xml.append("  <inheritance strategy=\"SINGLE_TABLE\"/>").append(EOLN);
                    }

                    DiscriminatorValue discriminatorValue = c.getAnnotation(DiscriminatorValue.class);
                    if (discriminatorValue != null)
                        xml.append("  <discriminator-value>").append(discriminatorValue.value()).append("</discriminator-value>").append(EOLN);

                    DiscriminatorColumn discriminatorColumn = c.getAnnotation(DiscriminatorColumn.class);
                    if (discriminatorColumn != null)
                        xml.append("  <discriminator-column name=\"").append(discriminatorColumn.value()).append("\"/>").append(EOLN);

                    writeAttributes(xml, c, keyAttributeName, embeddableTypes);

                    xml.append(" </entity>").append(EOLN);

                    entityClassInfo.add(xml.toString());
                } else {
                    entityClassNames.add(c.getName());
                }
            }

            for (Class<?> type : embeddableTypes) {
                StringBuilder xml = new StringBuilder(500).append(" <embeddable class=\"").append(type.getName()).append("\">").append(EOLN);
                writeAttributes(xml, type, null, null);
                xml.append(" </embeddable>").append(EOLN);
                entityClassInfo.add(xml.toString());
            }

            Map<String, ?> properties = Collections.singletonMap("io.openliberty.persistence.internal.entityClassInfo",
                                                                 entityClassInfo.toArray(new String[entityClassInfo.size()]));

            DatabaseStore dbstore = bc.getService(ref);
            PersistenceServiceUnit punit = dbstore.createPersistenceServiceUnit(loader,
                                                                                properties,
                                                                                entityClassNames.toArray(new String[entityClassNames.size()]));

            em = punit.createEntityManager();
            Metamodel model = em.getMetamodel();
            for (EntityType<?> entityType : model.getEntities()) {
                entityType.getName();//TODO
                LinkedHashMap<String, String> attributeNames = new LinkedHashMap<>();
                Set<String> collectionAttributeNames = new HashSet<String>();
                HashMap<String, List<Member>> attributeAccessors = new HashMap<>();
                Queue<Attribute<?, ?>> embeddables = new LinkedList<>();
                Queue<String> embeddablePrefixes = new LinkedList<>();
                Queue<List<Member>> embeddableAccessors = new LinkedList<>();
                for (Attribute<?, ?> attr : entityType.getAttributes()) {
                    String attributeName = attr.getName();
                    PersistentAttributeType attributeType = attr.getPersistentAttributeType();
                    if (PersistentAttributeType.EMBEDDED.equals(attributeType)) {
                        embeddables.add(attr);
                        embeddablePrefixes.add(attributeName);
                        embeddableAccessors.add(Collections.singletonList(attr.getJavaMember()));
                    } else {
                        attributeNames.put(attributeName.toUpperCase(), attributeName);
                        attributeAccessors.put(attributeName, Collections.singletonList(attr.getJavaMember()));
                        if (PersistentAttributeType.ELEMENT_COLLECTION.equals(attributeType))
                            collectionAttributeNames.add(attributeName);
                    }
                }

                for (Attribute<?, ?> attr; (attr = embeddables.poll()) != null;) {
                    String prefix = embeddablePrefixes.poll();
                    List<Member> accessors = embeddableAccessors.poll();
                    EmbeddableType<?> embeddable = model.embeddable(attr.getJavaType());
                    for (Attribute<?, ?> embAttr : embeddable.getAttributes()) {
                        String embeddableAttributeName = embAttr.getName();
                        String fullAttributeName = prefix + '.' + embeddableAttributeName;
                        List<Member> embAccessors = new LinkedList<>(accessors);
                        embAccessors.add(embAttr.getJavaMember());

                        PersistentAttributeType attributeType = embAttr.getPersistentAttributeType();
                        if (PersistentAttributeType.EMBEDDED.equals(attributeType)) {
                            embeddables.add(embAttr);
                            embeddablePrefixes.add(fullAttributeName);
                            embeddableAccessors.add(embAccessors);
                        } else {
                            // Allow the simple attribute name if it doesn't overlap
                            embeddableAttributeName = embeddableAttributeName.toUpperCase();
                            attributeNames.putIfAbsent(embeddableAttributeName, fullAttributeName);

                            // Allow a qualified name such as @OrderBy("address.street.name")
                            embeddableAttributeName = fullAttributeName.toUpperCase();
                            attributeNames.putIfAbsent(embeddableAttributeName, fullAttributeName);

                            // Allow a qualified name such as findByAddress_Street_Name
                            embeddableAttributeName = embeddableAttributeName.replace('.', '_');
                            attributeNames.putIfAbsent(embeddableAttributeName, fullAttributeName);

                            attributeAccessors.put(fullAttributeName, embAccessors);
                            if (PersistentAttributeType.ELEMENT_COLLECTION.equals(attributeType))
                                collectionAttributeNames.add(fullAttributeName);
                        }
                    }
                }

                // This works for version Fields, and might work for version getter/setter methods
                // but is debatable whether we should do it.
                //Member versionMember = null;
                //if (entityType.hasVersionAttribute())
                //    for (SingularAttribute<?, ?> attr : entityType.getSingularAttributes())
                //        if (attr.isVersion()) {
                //            versionMember = attr.getJavaMember(); // Field or Method, which could be used to update a passed-in entity with the new version number
                //            break;
                //        }

                Class<?> entityClass = entityType.getJavaType();

                if (trace && tc.isDebugEnabled())
                    Tr.debug(this, tc, "attribute names for " + entityClass, attributeNames);

                String keyAttributeName = keyAttributeNames.get(entityClass);
                Attribute<?, ?> keyAttribute = entityType.getAttribute(keyAttributeNames.get(entityType.getJavaType()));
                Member keyAccessor = keyAttribute.getJavaMember();

                if (trace && tc.isDebugEnabled())
                    Tr.debug(this, tc, "accessor for id " + keyAttribute, keyAccessor);

                EntityInfo entityInfo = new EntityInfo(entityType.getName(), //
                                entityClass, //
                                attributeAccessors, //
                                attributeNames, //
                                collectionAttributeNames, //
                                keyAttributeNames.get(entityClass), //
                                keyAccessor, //
                                punit);

                provider.entityInfoMap.computeIfAbsent(entityClass, EntityInfo::newFuture).complete(entityInfo);
            }
            if (trace && tc.isEntryEnabled())
                Tr.exit(this, tc, "run: define entities");
        } catch (RuntimeException x) {
            for (Class<?> entityClass : entities)
                provider.entityInfoMap.computeIfAbsent(entityClass, EntityInfo::newFuture).completeExceptionally(x);
            if (trace && tc.isEntryEnabled())
                Tr.exit(this, tc, "run: define entities", x);
            throw x;
        } catch (Exception x) {
            for (Class<?> entityClass : entities)
                provider.entityInfoMap.computeIfAbsent(entityClass, EntityInfo::newFuture).completeExceptionally(x);
            if (trace && tc.isEntryEnabled())
                Tr.exit(this, tc, "run: define entities", x);
            throw new RuntimeException(x);
        } catch (Error x) {
            for (Class<?> entityClass : entities)
                provider.entityInfoMap.computeIfAbsent(entityClass, EntityInfo::newFuture).completeExceptionally(x);
            if (trace && tc.isEntryEnabled())
                Tr.exit(this, tc, "run: define entities", x);
            throw x;
        } finally {
            if (em != null)
                em.close();
        }
    }

    private void writeAttributes(StringBuilder xml, Class<?> c, String keyAttributeName, List<Class<?>> embeddableTypes) {
        xml.append("  <attributes>").append(EOLN);

        List<Field> fields = new ArrayList<Field>();
        for (Class<?> superc = c; superc != null; superc = superc.getSuperclass()) {
            boolean isMappedSuperclass = superc.getAnnotation(MappedSuperclass.class) != null;
            if (isMappedSuperclass || superc == c)
                for (Field f : superc.getFields())
                    if (isMappedSuperclass || c.equals(f.getDeclaringClass()))
                        fields.add(f);
        }

        for (Field field : fields) {
            Id id = field.getAnnotation(Id.class);
            Column column = field.getAnnotation(Column.class);
            Generated generated = field.getAnnotation(Generated.class);
            Embeddable embeddable = field.getType().getAnnotation(Embeddable.class);

            String attributeName = field.getName();
            String columnName = column == null || column.value().length() == 0 ? //
                            id == null || id.value().length() == 0 ? null : id.value() : //
                            column.value();
            boolean isCollection = Collection.class.isAssignableFrom(field.getType());

            String columnType;
            if (embeddable == null) {
                columnType = id != null || keyAttributeName != null && keyAttributeName.equals(attributeName) ? "id" : //
                                "version".equals(attributeName) ? "version" : //
                                                isCollection ? "element-collection" : //
                                                                "basic";
            } else if (embeddableTypes == null) {
                throw new UnsupportedOperationException("TODO: Embeddedable within an Embeddable");
            } else {
                columnType = "embedded";
                embeddableTypes.add(field.getType());
            }

            xml.append("   <" + columnType + " name=\"" + attributeName + "\">").append(EOLN);
            if (columnName != null)
                xml.append("    <column name=\"" + columnName + "\"/>").append(EOLN);
            if (generated != null)
                xml.append("    <generated-value strategy=\"" + generated.value().name() + "\"/>").append(EOLN);
            xml.append("   </" + columnType + ">").append(EOLN);
        }

        xml.append("  </attributes>").append(EOLN);
    }
}
