package net.fortytwo.extendo.brain.rdf.classes.collections;

import net.fortytwo.extendo.brain.rdf.AtomCollection;
import net.fortytwo.extendo.brain.rdf.AtomRegex;
import net.fortytwo.extendo.brain.rdf.classes.Person;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public class PersonCollection extends AtomCollection {

    public PersonCollection() {
        super(
                "person-collection",
                Pattern.compile("(some people .+)" +
                        "|(.+ children)" +
                        "|(.+ social network)" +
                        "|(.+ friends)" +
                        "|(.+ family)" +
                        "|(the authors of .+)"),
                null,
                new AtomRegex(Arrays.asList(
                        new AtomRegex.El(null,
                                AtomRegex.Modifier.OneOrMore, Person.class, PersonCollection.class),
                        new AtomRegex.El(null,
                                AtomRegex.Modifier.ZeroOrMore)
                )));
    }
}
