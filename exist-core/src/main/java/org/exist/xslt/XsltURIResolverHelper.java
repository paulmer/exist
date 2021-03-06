/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2001-2020 The eXist-db Project
 * http://exist-db.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xslt;

import org.exist.repo.PkgXsltModuleURIResolver;
import org.exist.storage.BrokerPool;
import org.exist.util.EXistURISchemeURIResolver;
import org.exist.util.URIResolverHierarchy;

import javax.annotation.Nullable;
import javax.xml.transform.URIResolver;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilities for XSLT URI Resolution.
 *
 * @author <a href="mailto:adam@evolvedbinary.com">Adam Retter</a>
 */
public class XsltURIResolverHelper {

    /**
     * Get a URI Resolver for XSLT Modules.
     *
     * @param brokerPool the database
     * @param defaultResolver the default fallback resolver, or null
     * @param base the URI base, or null
     * @param avoidSelf true to avoid nesting {@link URIResolverHierarchy}
     *
     * @return the URIResolver, or null if there is no resolver
     */
    public static @Nullable URIResolver getXsltURIResolver(final BrokerPool brokerPool,
            @Nullable final URIResolver defaultResolver, @Nullable final String base, final boolean avoidSelf) {
        final List<URIResolver> resolvers = new ArrayList<>();

        if (base != null) {
            // database resolver
            resolvers.add(new EXistURISchemeURIResolver(new EXistURIResolver(brokerPool, base)));
        }

        // EXpath Pkg resolver
        brokerPool.getExpathRepo().map(repo -> resolvers.add(new PkgXsltModuleURIResolver(repo)));

        // default resolver
        if (defaultResolver != null) {
            if (avoidSelf) {
                if (!defaultResolver.getClass().getName().equals(URIResolverHierarchy.class.getName())) {
                    resolvers.add(defaultResolver);
                }
            } else {
                resolvers.add(defaultResolver);
            }
        }

        if (resolvers.size() > 0) {
            return new URIResolverHierarchy(resolvers);
        } else {
            return null;
        }
    }
}
