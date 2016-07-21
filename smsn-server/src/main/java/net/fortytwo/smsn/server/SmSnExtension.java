package net.fortytwo.smsn.server;

import com.tinkerpop.blueprints.KeyIndexableGraph;
import com.tinkerpop.blueprints.TransactionalGraph;
import com.tinkerpop.frames.FramedGraph;
import com.tinkerpop.rexster.RexsterResourceContext;
import com.tinkerpop.rexster.extension.AbstractRexsterExtension;
import com.tinkerpop.rexster.extension.ExtensionResponse;
import net.fortytwo.smsn.SemanticSynchrony;
import net.fortytwo.smsn.brain.Atom;
import net.fortytwo.smsn.brain.AtomGraph;
import net.fortytwo.smsn.brain.MyOtherBrain;
import net.fortytwo.smsn.brain.Filter;
import net.fortytwo.smsn.brain.Note;
import net.fortytwo.smsn.brain.NoteHistory;
import net.fortytwo.smsn.brain.NoteQueries;
import net.fortytwo.smsn.brain.Params;
import net.fortytwo.smsn.brain.wiki.NoteParser;
import net.fortytwo.smsn.brain.wiki.NoteWriter;
import org.json.JSONException;
import org.json.JSONObject;

import javax.servlet.http.HttpSession;
import javax.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Joshua Shinavier (http://fortytwo.net)
 */
public abstract class SmSnExtension extends AbstractRexsterExtension {
    protected static final Logger logger = Logger.getLogger(SmSnExtension.class.getName());

    public static final int MAX_VIEW_HEIGHT = 7;

    private static final String HISTORY_ATTR = "history";

    protected abstract ExtensionResponse performTransaction(RequestParams p) throws Exception;

    protected abstract boolean doesRead();

    protected abstract boolean doesWrite();

    private static final Map<KeyIndexableGraph, MyOtherBrain> brains = new HashMap<>();

    public synchronized static MyOtherBrain getBrain(final KeyIndexableGraph baseGraph)
            throws MyOtherBrain.BrainException {

        MyOtherBrain b = brains.get(baseGraph);

        if (null == b) {
            logger.info("instantiating MyOtherBrain with base graph " + baseGraph);
            AtomGraph bg = new AtomGraph(baseGraph);
            b = new MyOtherBrain(bg);
            b.startBackgroundTasks();
            brains.put(baseGraph, b);
        }

        return b;
    }

    protected RequestParams createParams(final RexsterResourceContext context,
                                  final KeyIndexableGraph graph) {
        RequestParams p = new RequestParams();
        p.baseGraph = graph;
        p.context = context;
        SecurityContext security = p.context.getSecurityContext();
        p.user = null == security ? null : security.getUserPrincipal();

        // TODO: reconsider security
        if (null == p.user) {
            //    logWarning("no security");
        }

        return p;
    }

    protected ExtensionResponse handleRequestInternal(final RequestParams p) {

        if (doesWrite() && !canWrite(p.user)) {
            return error("user does not have permission to for write operations");
        }

        if (doesRead() && null == p.filter) {
            return error("service reads from graph, but weight and sharability filter is not set");
        }

        String rootId = p.rootId;
        String styleName = p.styleName;

        try {
            p.map = new HashMap<>();

            if (!(p.baseGraph instanceof KeyIndexableGraph)) {
                return error("graph must be an instance of IndexableGraph");
            }

            if (null != p.wikiView) {
                // Force the use of the UTF-8 charset, which is apparently not chosen by Jersey
                // even when it is specified by the client in the Content-Type header, e.g.
                //    Content-Type: application/x-www-form-urlencoded;charset=UTF-8
                p.wikiView = new String(p.wikiView.getBytes("UTF-8"));
            }

            p.manager = new FramedGraph<>(p.baseGraph);
            p.brain = getBrain(p.baseGraph);
            p.queries = new NoteQueries(p.brain);
            p.parser = new NoteParser();
            p.writer = new NoteWriter();

            if (null != p.height) {
                if (p.height < 0) {
                    return error("height must be at least 0");
                }

                if (p.height > MAX_VIEW_HEIGHT) {
                    return error("height may not be more than 5");
                }

                p.map.put(Params.HEIGHT, "" + p.height);
            }

            if (null != p.filter) {
                p.map.put(Params.MIN_SHARABILITY, "" + p.filter.getMinSharability());
                p.map.put(Params.MAX_SHARABILITY, "" + p.filter.getMaxSharability());
                p.map.put(Params.DEFAULT_SHARABILITY, "" + p.filter.getDefaultSharability());
                p.map.put(Params.MIN_WEIGHT, "" + p.filter.getMinWeight());
                p.map.put(Params.MAX_WEIGHT, "" + p.filter.getMaxWeight());
                p.map.put(Params.DEFAULT_WEIGHT, "" + p.filter.getDefaultWeight());
            }

            if (null != rootId) {
                p.root = p.brain.getAtomGraph().getAtom(rootId);

                if (null == p.root) {
                    return error("root of view does not exist: " + rootId);
                }

                if (null != p.filter && !p.filter.isVisible(p.root.asVertex())) {
                    return error("root of view is not visible: " + rootId);
                }

                p.map.put(Params.ROOT, rootId);
            }

            p.map.put(Params.TITLE, null == p.root
                    || null == p.root.getValue()
                    || 0 == p.root.getValue().length() ? "[no title]" : p.root.getValue());

            if (null != styleName) {
                p.style = NoteQueries.lookupStyle(styleName);
                p.map.put(Params.STYLE, p.style.getName());
            }

            // Force manual transaction mode (provided that the graph is transactional)
            boolean manual = doesWrite() && p.baseGraph instanceof TransactionalGraph;

            boolean normal = false;

            try {
                ExtensionResponse r = performTransaction(p);
                normal = true;

                // Note: currently, all activities are logged, but the log is not immediately flushed
                //       unless the transaction succeeds.
                if (null != p.brain.getActivityLog()) {
                    p.brain.getActivityLog().flush();
                }

                return r;
            } finally {
                if (doesWrite()) {
                    if (manual) {
                        if (!normal) {
                            SemanticSynchrony.logWarning("rolling back transaction");
                        }

                        ((TransactionalGraph) p.baseGraph).stopTransaction(normal
                                ? TransactionalGraph.Conclusion.SUCCESS
                                : TransactionalGraph.Conclusion.FAILURE);
                    } else if (!normal) {
                        SemanticSynchrony.logWarning(
                                "failed update of non-transactional graph. Data integrity is not guaranteed");
                    }
                }
            }
        } catch (Exception e) {
            SemanticSynchrony.logSevere("request failed", e);
            return error(e.getMessage());
        }
    }

    protected Filter createFilter(final Principal user,
                                  final float minWeight,
                                  final float maxWeight,
                                  final float defaultWeight,
                                  final float minSharability,
                                  final float maxSharability,
                                  final float defaultSharability) {

        float m = findMinAuthorizedSharability(user, minSharability);
        return new Filter(minWeight, maxWeight, defaultWeight, m, maxSharability, defaultSharability);
    }

    protected org.codehaus.jettison.json.JSONObject toJettison(JSONObject j) throws IOException {
        try {
            return new org.codehaus.jettison.json.JSONObject(j.toString());
        } catch (org.codehaus.jettison.json.JSONException e) {
            throw new IOException(e);
        }
    }

    protected void addView(final Note n,
                           final RequestParams p) throws IOException {
        JSONObject json;

        try {
            json = p.writer.toJSON(n);
        } catch (JSONException e) {
            throw new IOException(e);
        }

        p.map.put(Params.VIEW, toJettison(json));
    }

    public static float findMinAuthorizedSharability(final Principal user,
                                                     final float minSharability) {
        // TODO
        float minAuth = (null == user)
                ? 0.0f
                : !user.getName().equals("josh")
                ? 0.75f : 0;

        return Math.max(minSharability, minAuth);
    }

    protected boolean canWrite(final Principal user) {
        // TODO
        return null == user || user.getName().equals("josh");
    }

    private ExtensionResponse error(final String message) {
        logger.log(Level.WARNING, "SmSn extension error: " + message);
        return ExtensionResponse.error(message);
    }

    private NoteHistory getNotesHistory(final RexsterResourceContext context) {
        HttpSession session = context.getRequest().getSession();
        NoteHistory h = (NoteHistory) session.getAttribute(HISTORY_ATTR);
        if (null == h) {
            h = new NoteHistory();
            session.setAttribute(HISTORY_ATTR, h);
        }

        return h;
    }

    protected void addToHistory(final String rootId,
                                final RexsterResourceContext context) {
        NoteHistory h = getNotesHistory(context);
        h.visit(rootId);
    }

    protected List<String> getHistory(final RexsterResourceContext context,
                                      final AtomGraph graph,
                                      final Filter filter) {
        NoteHistory h = getNotesHistory(context);
        return h.getHistory(100, true, graph, filter);
    }

    protected class RequestParams {
        public KeyIndexableGraph baseGraph;
        public MyOtherBrain brain;
        public RexsterResourceContext context;
        public String data;
        public Integer height;
        public String file;
        public Filter filter;
        public String format;
        public boolean includeTypes;
        public JSONObject jsonView;
        public FramedGraph<KeyIndexableGraph> manager;
        public Map<String, Object> map;
        public Integer maxResults;
        public NoteParser parser;
        public String propertyName;
        public Object propertyValue;
        public NoteQueries queries;
        public String query;
        public NoteQueries.QueryType queryType;
        public Atom root;
        public String rootId;
        public NoteQueries.ViewStyle style;
        public String styleName;
        public Principal user;
        public Integer valueCutoff;
        public String wikiView;
        public NoteWriter writer;
    }
}
