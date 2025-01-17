/*******************************************************************************
 * Copyright (c) 2019 IBM Corporation and others.
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
package com.ibm.ws.jaxws.internal;

import java.util.ResourceBundle;

/**
 *
 */
public class JaxWsCommonConstants {

    public static final String TR_RESOURCE_BUNDLE = "com.ibm.ws.jaxws.clientcontainer.internal.resources.JaxWsClientContainerMessages";

    public static final String TR_GROUP = "JaxWsClientContainer";

    public static final ResourceBundle messages = ResourceBundle.getBundle(TR_RESOURCE_BUNDLE);

}
