/*******************************************************************************
 * Copyright (c) 2023 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package test.jakarta.data.jpa.web;

import java.util.List;
import java.util.stream.Stream;

import jakarta.data.repository.OrderBy;
import jakarta.data.repository.Repository;

/**
 *
 */
@Repository
public interface Employees {

    void deleteByLastName(String lastName);

    Employee findByBadgeNumber(long badgeNumber);

    @OrderBy("badge.number")
    Stream<Employee> findByFirstNameLike(String pattern);

    List<Employee> findByFirstNameStartsWithOrderByIdDesc(String prefix);

    Employee findById(long badgeNumber);

    void save(Employee e);
}