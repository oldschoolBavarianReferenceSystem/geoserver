/* (c) 2014 Open Source Geospatial Foundation - all rights reserved
 * (c) 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.kml.sequence;

import java.util.EmptyStackException;
import java.util.List;

import org.geoserver.kml.KmlEncodingContext;
import org.geoserver.kml.decorator.KmlDecoratorFactory.KmlDecorator;
import org.geoserver.kml.utils.ScaleStyleVisitor;
import org.geoserver.kml.utils.SymbolizerCollector;
import org.geoserver.wms.WMSMapContent;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.filter.function.EnvFunction;
import org.geotools.map.FeatureLayer;
import org.geotools.map.Layer;
import org.geotools.styling.Style;
import org.geotools.styling.Symbolizer;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import de.micromata.opengis.kml.v_2_2_0.Feature;
import de.micromata.opengis.kml.v_2_2_0.Placemark;

/**
 * Creates a sequence of Placemark objects mapping the vector contents of a layer
 * 
 * @author Andrea Aime - GeoSolutions
 */
public class FeatureSequenceFactory implements SequenceFactory<Feature> {
    
    static final String OUTPUT_MODE = "kmlOutputMode";
    
    static final String VECTOR_MODE = "vector";

    private SimpleFeatureCollection features;

    private List<KmlDecorator> callbacks;

    private Style simplified;

    private KmlEncodingContext context;

    public FeatureSequenceFactory(KmlEncodingContext context, FeatureLayer layer) {
        this.context = context;
        this.features = context.getCurrentFeatureCollection();
        WMSMapContent mapContent = context.getMapContent();

        // prepare the encoding context
        context.setCurrentLayer(layer);
        

        // prepare the callbacks
        callbacks = context.getDecoratorsForClass(Placemark.class);

        // prepare the style for this layer
        simplified = getSimplifiedStyle(mapContent, layer);
    }

    private Style getSimplifiedStyle(WMSMapContent mc, Layer layer) {
        ScaleStyleVisitor visitor = new ScaleStyleVisitor(mc.getScaleDenominator(), 
                (SimpleFeatureType) layer.getFeatureSource().getSchema());
        try {
            layer.getStyle().accept(visitor);
            return (Style) visitor.getCopy();
        } catch (EmptyStackException e) {
            return null;
        }
    }

    @Override
    public Sequence<Feature> newSequence() {
        return new FeatureGenerator(simplified != null ? context.openIterator(features) : null);
    }

    public class FeatureGenerator implements Sequence<Feature> {

        private FeatureIterator fi;

        public FeatureGenerator(FeatureIterator fi) {
            if(fi != null) {
                // until we are scrolling this generator we are in vector mode
                EnvFunction.setLocalValue(OUTPUT_MODE, VECTOR_MODE);
                this.fi = fi;
            }
        }

        @Override
        public Feature next() {
            // already reached the end?
            if (fi == null) {
                return null;
            }

            while (fi.hasNext()) {
                boolean featureRetrieved = false;
                try {
                    // grab the next feature, with a sentinel to tell us whether there was an
                    // exception
                    SimpleFeature sf = (SimpleFeature) fi.next();
                    featureRetrieved = true;
                    context.setCurrentFeature(sf);

                    List<Symbolizer> symbolizers = getSymbolizers(simplified, sf);
                    if (symbolizers.size() == 0) {
                        // skip layers that have no active symbolizers
                        continue;
                    }
                    context.setCurrentSymbolizers(symbolizers);

                    // only create the basic placemark here, the rest is delegated to decorators
                    Placemark pm = new Placemark();
                    pm.setId(sf.getID());

                    // call onto the decorators
                    for (KmlDecorator callback : callbacks) {
                        pm = (Placemark) callback.decorate(pm, context);
                        if (pm == null) {
                            // we have to skip this one
                            continue;
                        }
                    }

                    return pm;
                } finally {
                    if (!featureRetrieved) {
                        // an exception has occurred, release the feature iterator
                        context.closeIterator(fi);
                        EnvFunction.setLocalValue(OUTPUT_MODE, null);
                    }
                }
            }

            // did we reach the end just now?
            if (!fi.hasNext()) {
                // clean up the output mode, the next layer might be encoded as a raster overlay
                EnvFunction.setLocalValue(OUTPUT_MODE, null);
                context.closeIterator(fi);
            }
            return null;
        }

        private List<Symbolizer> getSymbolizers(Style style, SimpleFeature sf) {
            SymbolizerCollector collector = new SymbolizerCollector(sf);
            style.accept(collector);
            return collector.getSymbolizers();
        }

    }

}
