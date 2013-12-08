package net.fortytwo.extendo.rdf.vocab;

import org.openrdf.model.URI;
import org.openrdf.model.impl.URIImpl;

/**
 * @author Joshua Shinavier (http://fortytwo.net).
 */
public interface ExtendoGesture {
    public static final String NAMESPACE = "http://fortytwo.net/2013/extendo/gesture#";

    // classes
    public static final URI
            // note: other specific gestures are defined in the ontology but are not yet used
            GenericBatonGesture = new URIImpl(NAMESPACE + "GenericBatonGesture"),
            Gesture = new URIImpl(NAMESPACE + "Gesture");

    // properties
    public static final URI
            expressedBy = new URIImpl(NAMESPACE + "expressedBy"),
            recognizedAt = new URIImpl(NAMESPACE + "recognizedAt");

}