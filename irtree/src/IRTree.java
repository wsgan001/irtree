/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Stack;
import util.FileUtils;
import util.Primitive;
import util.Util;
import util.cache.CacheItem;
import util.cache.MaxHeap;
import util.file.ColumnFileException;
import util.file.DataNotFoundException;
import util.file.EntryStorage;
import util.file.IntegerEntry;
import util.sse.InvertedFile;
import util.sse.PostingListEntry;
import util.sse.SSEExeption;
import util.sse.Term;
import util.sse.Vector;
import util.sse.Vocabulary;
import xxl.core.collections.MapEntry;
import xxl.core.collections.containers.io.ConverterContainer;
import xxl.core.cursors.Cursors;
import xxl.core.cursors.filters.Filter;
import xxl.core.cursors.identities.TeeCursor;
import xxl.core.cursors.mappers.Mapper;
import xxl.core.cursors.sources.Enumerator;
import xxl.core.cursors.unions.Sequentializer;
import xxl.core.functions.Function;
import xxl.core.indexStructures.Descriptor;
import xxl.core.indexStructures.Descriptors;
import xxl.core.indexStructures.ORTree;
import xxl.core.indexStructures.RTree;
import xxl.core.indexStructures.Tree;
import xxl.core.io.converters.ConvertableConverter;
import xxl.core.io.converters.IntegerConverter;
import xxl.core.predicates.Predicate;
import xxl.core.spatial.KPE;
import xxl.core.spatial.points.Point;
import xxl.core.spatial.rectangles.DoublePointRectangle;
import xxl.core.spatial.rectangles.Rectangle;
import xxl.util.StarRTree;
import xxl.util.TreeHeapEntry;
import xxl.util.statistics.DefaultStatisticCenter;
import xxl.util.statistics.StatisticCenter;

/**
 *
 * @author joao
 */
public class IRTree extends StarRTree {

    public static String INVERTED_FILES_DIRECTORY = "ifDir";
    //Vocabulary that maps large id numbers to small ones, required by the block column files
    protected Vocabulary nodeVocabulary;
    //Vocabulary that maps large id numbers to small ones, required by the block column files
    protected Vocabulary objVocabulary;
    //maps object id (from objVocabulary) to the number of distinct terms in the document
    protected EntryStorage<IntegerEntry> objInfo;
    //Vocabulary that maps terms to an id.
    protected Vocabulary termVocabulary;
    //maps term id (from termVocabulary) to the number of documents that has the term (document frequency of the term)
    protected EntryStorage<IntegerEntry> termInfo;
    //Manages a cache of vectors
    protected VectorCacheManager vectorCache;
    private int vectorCacheSize;
    //Status that indicates when a rebuild is needed and whether the update must be propagated
    private boolean up = true;
    private LinkedList<IndexEntry> rebuild = new LinkedList<>();
    //Store the size to avoid recomputing
    private long invertedFilesSizeInBytes;
    private long lastTreeSizeInBytes;
    //Variable that indicates that the RTree is under constructions. Thus
    //The inverted files are not updated after every insert
    protected final boolean constructionTime;
    //The size of the datablocks used to store vectors of each data or node
    final int vectorBlockSize;

    public IRTree(StatisticCenter statisticCenter, String id, String outputPath,
            int dimensions, int bufferSize, int blockSize, int minCapacity,
            int maxCapacity, int invertedFilesBlockSize, int vectorBlockSize,
            int vectorCacheSize, boolean constructionTime) {
        super(statisticCenter, id, outputPath, dimensions, bufferSize, blockSize, minCapacity, maxCapacity);
        this.vectorCacheSize = vectorCacheSize;
        this.constructionTime = constructionTime;
        this.vectorBlockSize = vectorBlockSize;
    }

    @Override
    public void open() throws IOException, ClassNotFoundException {
        super.open();

        FileUtils.createDirectories(INVERTED_FILES_DIRECTORY);

        try {
            termVocabulary = new Vocabulary(getOutputPath() + "/termVocabulary.txt");
            termVocabulary.open();
            termInfo = new EntryStorage<>(null, "termInfo",
                    getOutputPath() + "/termInfo", Integer.SIZE, IntegerEntry.FACTORY);
            termInfo.open();

            nodeVocabulary = new Vocabulary(getOutputPath() + "/nodeVocabulary.txt");
            nodeVocabulary.open();

            objVocabulary = new Vocabulary(getOutputPath() + "/objectVocabulary.txt");
            objVocabulary.open();

            objInfo = new EntryStorage<>(null, "objInfo",
                    getOutputPath() + "/objInfo", Integer.SIZE, IntegerEntry.FACTORY);
            objInfo.open();

            vectorCache = new VectorCacheManager(objVocabulary,
                    nodeVocabulary, vectorBlockSize, getOutputPath() + "/vector", vectorCacheSize);
            vectorCache.open();
        } catch (SSEExeption | ColumnFileException ex) {
            throw new RuntimeException(ex);
        }
    }

    public Iterator<TreeHeapEntry> search(Point queryLocation, String queryKeywords,
            int k, double maxDist, double alpha) throws SSEExeption, IOException, ColumnFileException, DataNotFoundException {

        Vector queryVector = new Vector();
        int numWords = Vector.vectorize(queryVector, queryKeywords, termVocabulary);
        double queryLength = Vector.computeQueryWeight(queryVector, getNumObjects(), termInfo);

        ArrayList<TreeHeapEntry> result = new ArrayList<>();
        HashMap<Integer, Vector> candidates; //stores the candidates of a node in term of keyword relevance
        MaxHeap<TreeHeapEntry> heap = new MaxHeap<>();
        TreeHeapEntry entry;
        TreeHeapEntry heapEntry;

        heap.add(new TreeHeapEntry(rootEntry()));

        while (!heap.isEmpty() && result.size() < k) {
            entry = heap.poll();

            if (entry.isData()) {
                result.add(entry);
            } else {
                candidates = getCandidates((IndexEntry) entry.getItem(), queryVector);

                for (Iterator it = ((IndexEntry) entry.getItem()).get().entries(); it.hasNext();) {
                    heapEntry = new TreeHeapEntry(it.next());
                    if (candidates.containsKey(heapEntry.getId())) {
                        heapEntry.setScore(
                                score(heapEntry.getMBR().minDistance(queryLocation, 2),
                                candidates.get(heapEntry.getId()), queryVector,
                                queryLength, maxDist, alpha));
                        heap.add(heapEntry);
                    }
                }
            }
        }
        return result.iterator();
    }

    /**
     *
     * @param distanceToQueryPoint the Euclidian distance to the query location
     * @param candidate the candidat that has at least one query keyword
     * @param query the terms of the query with number of occurrences
     * @param queryLength the total number of terms in the query
     * @param alpha the relevance of spatial versus textual sore
     * @param docFrequency the frequency of each query term in the collection.
     * The number of objects in which the term appears.
     * @return the score
     * @throws SSEExeption
     * @throws IOException
     * @throws ColumnFileException
     * @throws DataNotFoundException
     */
    private double score(double distanceToQueryPoint, Vector candidate,
            Vector query, double queryLenght, double maxDist, double alpha)
            throws SSEExeption, IOException, ColumnFileException, DataNotFoundException {

        double spatialProximity = 1 - distanceToQueryPoint / maxDist;

        int termId;
        double textRelevance = 0;

        Iterator<Term> candidateTerms = candidate.iterator();
        Term queryTerm;
        Term candidateTerm;
        while (candidateTerms.hasNext()) {
            candidateTerm = candidateTerms.next();  //Info about the term in the candidate
            termId = candidateTerm.getTermId();
            queryTerm = query.getTerm(termId); //Info about the term in the query

            textRelevance += candidateTerm.getWeight() * (queryTerm.getWeight() / queryLenght);
        }
        //return alpha*textRelevance(alpha+distance);
        return alpha * spatialProximity + (1 - alpha) * textRelevance;
    }

    /**
     * Get the candidate entries (that has at least one relevant keyword) from
     * the inverted file of the node.
     *
     * @param node
     * @param queryVector
     * @return
     * @throws SSEExeption
     */
    public HashMap<Integer, Vector> getCandidates(IndexEntry node, Vector queryVector) throws SSEExeption {
        HashMap<Integer, Vector> candidates = new HashMap<>();

        InvertedFile nodeInvertedFile = new InvertedFile(null, getNodeInvFile(node));
        nodeInvertedFile.open();

        Vector candidate; //keep the terms of the candidate that match with the query.

        Term keyword;
        PostingListEntry entry;
        for (Iterator<Term> queryKeywords = queryVector.iterator(); queryKeywords.hasNext();) {
            keyword = queryKeywords.next();
            //Get the nodes that have at least one occurrence of the term.
            Iterator<PostingListEntry> it = nodeInvertedFile.getPostingListIterator(keyword.getTermId());
            while (it != null && it.hasNext()) {
                entry = it.next();
                candidate = candidates.get(entry.getDocId());
                if (candidate == null) {
                    candidate = new Vector(entry.getDocId());
                    candidates.put(entry.getDocId(), candidate);
                }
                candidate.add(new Term(keyword.getTermId(), entry.getWeight()));
            }
        }

        nodeInvertedFile.close();

        return candidates;
    }

    public Vocabulary getTermVocabulary() {
        return termVocabulary;
    }

    public int getNumObjects() throws ColumnFileException {
        try {
            return (int) objInfo.size();
        } catch (IOException e) {
            throw new ColumnFileException(e);
        }
    }

    public int getDocumentFrequency(int termId) throws SSEExeption,
            ColumnFileException, IOException, DataNotFoundException {
        IntegerEntry entry = termInfo.getEntry(termId);
        if (entry == null) {
            throw new DataNotFoundException("Entry refering to termId="
                    + termId + " was not found.");
        }
        return entry.getValue();
    }

    @Override
    public Rectangle rectangle(Object entry) {
        return new NodeTextRectangle((TextRectangle) descriptor(entry), -1, null);
    }

    @Override
    protected ConverterContainer createConverterContainer(final int dimensions) {
        return new ConverterContainer(
                fileContainer,
                this.nodeConverter(new ConvertableConverter(
                new Function() {
            @Override
            public Object invoke() {
                return new KPE(new TextRectangle(dimensions));
            }
        }), this.indexEntryConverter(
                new ConvertableConverter(
                new Function() {
            @Override
            public Object invoke() {
                return new NodeTextRectangle(dimensions);
            }
        }))));
    }

    @Override
    public void insert(Object data) {
        indexObject((TextRectangle) ((KPE) data).getData());
        super.insert(data);
    }

    @Override
    protected IndexEntry chooseLeaf(Descriptor descriptor, int targetLevel, Stack path) {
        IndexEntry node = (IndexEntry) super.chooseLeaf(descriptor, targetLevel, path);

        update(node, new int[]{((TextRectangle) descriptor).getId()}, true);

        return node;
    }

    private void update(IndexEntry node, int[] sourceIds, boolean isDataVector) {
        try {
            int nodeId = Integer.parseInt(node.id().toString());

            HashMap<Integer, List<PostingListEntry>> invertedFile = null;

            if (!constructionTime) {
                invertedFile = new HashMap<>();
            }

            Vector nodeVector = getNodeVector(nodeId);

            for (int sourceId : sourceIds) {
                Vector sourceVector = isDataVector ? getDataVector(sourceId) : getNodeVector(sourceId);

                nodeVector.agg(sourceVector);

                if (!constructionTime) {
                    union(invertedFile, sourceVector, sourceId);
                }

            }

            vectorCache.putNodeVector(nodeVector);

            if (!constructionTime) {
                updateInvertedFile(node, invertedFile);
            }

        } catch (SSEExeption | IOException | ColumnFileException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    protected Entry grow(Object entry) {
        Entry e = super.grow(entry);

        IndexEntry root = (IndexEntry) e.getKey();
        ((TextRectangle) root.descriptor()).setId(Integer.parseInt(root.id().toString()));

        if (entry instanceof KPE) {
            update(root, new int[]{((TextRectangle) ((KPE) entry).getData()).getId()}, true);
        }

        return e;
    }

    @Override
    protected IndexEntry up(Stack path) {
        IndexEntry node = (IndexEntry) super.up(path);

        if (up && !path.isEmpty()) {
            try {
                MapEntry pathEntry = (MapEntry) path.peek();

                IndexEntry upperNode = (IndexEntry) pathEntry.getKey();

                int upperNodeId = Integer.parseInt(upperNode.id().toString());
                int thisNodeId = Integer.parseInt(node.id().toString());

                Vector thisNodeVector;

                thisNodeVector = getNodeVector(thisNodeId);

                Vector upperNodeVector = getNodeVector(upperNodeId);

                if (!upperNodeVector.contains(thisNodeVector)) {
                    upperNodeVector.agg(thisNodeVector);
                    vectorCache.putNodeVector(upperNodeVector);

                    if (!constructionTime) {
                        updateInvertedFile(upperNode,
                                union(new HashMap<Integer, List<PostingListEntry>>(),
                                thisNodeVector, thisNodeId));
                    }
                } else {
                    up = false;
                }
            } catch (NumberFormatException | IOException | ColumnFileException | SSEExeption ex) {
                throw new RuntimeException(ex);
            }
        }

        if (path.isEmpty()) {
            up = true;
        }
        return node;
    }

    @Override
    public Tree.Node createNode(int level) {
        return new Node().initialize(level, new LinkedList());
    }

    protected Double areaCost(Rectangle rectangle, Rectangle newRectangle) {
        Rectangle temp = Descriptors.union(rectangle, newRectangle);
        return new Double(temp.area() - rectangle.area());
    }

    protected Double wasteArea(Rectangle r0, Rectangle r1) {
        return new Double(Descriptors.union(r0, r1).area()
                - r0.area() - r1.area());
    }

    public class Node extends RTree.Node {

        @Override
        protected Collection redressOverflow(Stack path, List newIndexEntries, boolean up) {
            Collection list = super.redressOverflow(path, newIndexEntries, up);

            if (!rebuild.isEmpty()) {
                IndexEntry entry;
                while (!rebuild.isEmpty()) {
                    entry = rebuild.removeFirst();
                    rebuildNodeVector(entry, entry.get().entries(), true);
                }
                entry = (IndexEntry) indexEntry(path);

                rebuildNodeVector(entry, entry.get().entries(), false);
            }

            return list;
        }

        @Override
        protected SplitInfo redressOverflow(Stack path, Tree.Node parentNode, List newIndexEntries) {
            SplitInfo split = (SplitInfo) super.redressOverflow(path, parentNode, newIndexEntries);

            IndexEntry newIndexEntry = (IndexEntry) newIndexEntries.get(newIndexEntries.size() - 1);
            ((TextRectangle) newIndexEntry.descriptor()).setId(Integer.parseInt(newIndexEntry.id().toString()));
            rebuild.add(newIndexEntry);
            return split;
        }

        @Override
        protected ORTree.IndexEntry chooseSubtree(Descriptor descriptor, Iterator minima) {
            final Rectangle dataRectangle = (Rectangle) descriptor;
            TeeCursor validEntries = new TeeCursor(minima);
            validEntries.open();

            minima = Cursors.minima(validEntries.cursor(),
                    new Function() {
                @Override
                public Object invoke(Object object) {
                    return areaCost((Rectangle) descriptor(object), (Rectangle) dataRectangle);
                }
            }).iterator();

            validEntries.close();

            return (IndexEntry) minima.next();
        }

        /**
         * The split is based on the split method of QuadraticRTree
         *
         * @param path
         * @return
         */
        @Override
        @SuppressWarnings("empty-statement")
        protected Tree.Node.SplitInfo split(final Stack path) {
            try {
                final Node node = (Node) node(path);
                int number = node.number(), minNumber = node.splitMinNumber(), maxNumber = node.splitMaxNumber();
                Iterator seeds = new Sequentializer(
                        new Mapper(node.entries(),
                        new Function() {
                    int index = 0;

                    @Override
                    public Object invoke(final Object entry1) {
                        return new Mapper(((List) node.entries).listIterator(++index),
                                new Function() {
                            @Override
                            public Object invoke(Object entry2) {
                                return new Object[]{entry1, entry2};
                            }
                        });
                    }
                }));
                final Object[] seed = (Object[]) Cursors.maxima(seeds,
                        new Function() {
                    @Override
                    public Object invoke(Object seed) {
                        return wasteArea((Rectangle) descriptor(((Object[]) seed)[0]),
                                (Rectangle) descriptor(((Object[]) seed)[1]));
                    }
                }).getFirst();
                final Rectangle[] nodesMBRs = new Rectangle[]{rectangle(seed[0]), rectangle(seed[1])};

                //OUR CODE STARTS
                ((NodeTextRectangle) nodesMBRs[0]).setId(Integer.MAX_VALUE - 1);
                Vector v1 = getNodeVector(((NodeTextRectangle) nodesMBRs[0]).getId());
                v1.agg(getVector((TextRectangle) descriptor(((Object[]) seed)[0])));
                vectorCache.putNodeVector(v1);

                ((NodeTextRectangle) nodesMBRs[1]).setId(Integer.MAX_VALUE);
                Vector v2 = getNodeVector(((NodeTextRectangle) nodesMBRs[1]).getId());
                v2.agg(getVector((TextRectangle) descriptor(((Object[]) seed)[1])));
                vectorCache.putNodeVector(v2);

                final Vector[] vectors = new Vector[]{v1, v2};
                //OUR CODE ENDS

                final Collection[] nodesEntries = new Collection[]{node.entries, this.entries};
                final List remainingEntries = Cursors.toList(
                        new Filter(node.entries(),
                        new Predicate() {
                    @Override
                    public boolean invoke(Object entry) {
                        return entry != seed[0] && entry != seed[1];
                    }
                }),
                        new ArrayList(number - 2));

                node.entries.clear();
                for (int i = 0; i < 2; nodesEntries[i].add(seed[i++]));
                while (!remainingEntries.isEmpty() && node.number() != maxNumber && number - number() != minNumber) {
                    int entryIndex = ((Integer) Cursors.maxima(new Enumerator(remainingEntries.size()),
                            new Function() {
                        @Override
                        public Object invoke(Object object) {
                            Rectangle rectangle = (Rectangle) descriptor(remainingEntries.get(((Integer) object).intValue()));
                            return new Double(Math.abs(areaCost(nodesMBRs[1], rectangle)
                                    - areaCost(nodesMBRs[0], rectangle)));
                        }
                    }).getFirst()).intValue();
                    Object entry = remainingEntries.set(entryIndex, remainingEntries.get(remainingEntries.size() - 1));
                    Rectangle rectangle = (Rectangle) descriptor(entry);
                    double areaEnlargementDifference, areaDifference;
                    int index = (areaEnlargementDifference = areaCost(nodesMBRs[1], rectangle) - areaCost(nodesMBRs[0], rectangle)) < 0
                            || areaEnlargementDifference == 0 && ((areaDifference = nodesMBRs[1].area() - nodesMBRs[0].area()) < 0
                            || areaDifference == 0 && number() < node.number()) ? 1 : 0;

                    nodesEntries[index].add(entry);

                    //OUR CODE STARTS
                    vectors[index].agg(getVector((TextRectangle) rectangle));
                    vectorCache.putNodeVector(vectors[index]);
                    //OUR CODE ENDS

                    nodesMBRs[index].union(rectangle);
                    remainingEntries.remove(remainingEntries.size() - 1);
                }

                //OUR CODE STARTS
                vectorCache.deleteNodeVector(v1.getId());
                vectorCache.deleteNodeVector(v2.getId());
                //OUR CODE ENDS

                if (!remainingEntries.isEmpty()) {
                    int index = node.number() == maxNumber ? 1 : 0;

                    for (Iterator it = remainingEntries.iterator(); it.hasNext();) {
                        Object entry = it.next();

                        nodesEntries[index].add(entry);
                        nodesMBRs[index].union((Rectangle) descriptor(entry));
                    }
                }

                IndexEntry e = ((IndexEntry) indexEntry(path));
                e.initialize(nodesMBRs[0]);

                //OUR CODE STARTS
                ((TextRectangle) e.descriptor()).setId(Integer.parseInt(e.id().toString()));
                rebuild.add(e);
                //OUR CODE ENDS

                return new SplitInfo(path).initialize(nodesMBRs[1]);
            } catch (IOException | ColumnFileException | SSEExeption | NumberFormatException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
        try {
            //System.out.println("TermInfo: "+ termInfo.toString());
            //System.out.println("\nObjectInfo: "+ objInfo.toString());

            termVocabulary.close();
            termInfo.close();

            nodeVocabulary.close();

            objVocabulary.close();
            objInfo.close();

            if (vectorCache != null) {
                vectorCache.close();
            }
        } catch (SSEExeption | ColumnFileException ex) {
            throw new IOException(ex);
        }
    }

    protected void indexObject(TextRectangle dataRectangle) {
        try {

            int objId = ((TextRectangle) dataRectangle).getId();

            Vector vector = getDataVector(objId);

            int numWords = Vector.vectorize(vector, ((TextRectangle) dataRectangle).getText(), termVocabulary);
            statisticCenter.getTally("avgNumDistinctTerms").update(vector.size());

            double docLength = Vector.computeDocWeight(vector);
            statisticCenter.getCount("totalNumberOfWords").update(numWords);

            //Remove the text from the rectangle since it is not required anymore.
            ((TextRectangle) dataRectangle).cleanText();

            objInfo.putEntry(objVocabulary.getId(IRTree.getStrId(objId, true)),
                    new IntegerEntry(numWords));

            Term term;
            for (Iterator<Term> it = vector.iterator(); it.hasNext();) {
                term = it.next();

                //We normalize weight by the document lenght, which gives the impact of t
                term.setWeight(term.getWeight() / docLength);

                IntegerEntry entry = termInfo.getEntry(term.getTermId());
                if (entry == null) {
                    entry = new IntegerEntry(1);
                } else {
                    entry.setValue(entry.getValue() + 1);
                }
                termInfo.putEntry(term.getTermId(), entry);
            }

            vectorCache.putDataVector(vector);
        } catch (SSEExeption | IOException | ColumnFileException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void rebuildNodeVector(IndexEntry node, Iterator entries, boolean delete) {
        try {
            if (delete) {
                vectorCache.deleteNodeVector(Integer.parseInt(node.id().toString()));
            }

            if (!constructionTime) {
                InvertedFile.delete(getNodeInvFile(node));
            }
        } catch (NumberFormatException | ColumnFileException | IOException | SSEExeption ex) {
            throw new RuntimeException(ex);
        }
        ArrayList<Integer> ids = new ArrayList<>();
        if (node.level() == 0) {//leaf node
            while (entries.hasNext()) {
                KPE kpe = (KPE) entries.next();
                ids.add(Integer.parseInt(kpe.getID().toString()));
            }
            update(node, Primitive.toArray(ids), true);
        } else {
            while (entries.hasNext()) {
                IndexEntry entry = (IndexEntry) entries.next();
                ids.add(Integer.parseInt(entry.id().toString()));
            }
            update(node, Primitive.toArray(ids), false);
        }
    }

    private HashMap<Integer, List<PostingListEntry>> union(
            HashMap<Integer, List<PostingListEntry>> invertedFile, Vector vector, int docId) {

        List<PostingListEntry> invertedFileList;

        //thisIndex is super set of other index iff for each term t in other
        //index there is a term t in thisIndex whose frequence is higher or
        //equal the frequence of t in otherIndex
        Iterator<Term> it = vector.iterator();
        Term term;

        while (it.hasNext()) {
            term = it.next();
            invertedFileList = invertedFile.get(term.getTermId());
            if (invertedFileList == null) {
                invertedFileList = new ArrayList<>();
                invertedFile.put(term.getTermId(), invertedFileList);
            }

            int pos = 0;
            if (!invertedFileList.isEmpty()) {
                PostingListEntry entry = invertedFileList.get(pos++);
                while (docId < entry.getDocId() && pos < invertedFileList.size()) {
                    entry = invertedFileList.get(pos++);
                }
            }
            invertedFileList.add(pos, new PostingListEntry(docId, term.getWeight()));
        }
        return invertedFile;
    }

    protected Vector getDataVector(int id) throws IOException, ColumnFileException, SSEExeption {
        return vectorCache.getDataVector(id);
    }

    protected Vector getNodeVector(int id) throws IOException, ColumnFileException, SSEExeption {
        return vectorCache.getNodeVector(id);
    }

    protected Vector getVector(Rectangle rec) throws IOException, ColumnFileException, SSEExeption {
        return rec instanceof NodeTextRectangle
                ? vectorCache.getNodeVector(((TextRectangle) rec).getId())
                : vectorCache.getDataVector(((TextRectangle) rec).getId());
    }

    protected String getNodeInvFile(IndexEntry node) throws SSEExeption {
        int nodeId = Integer.parseInt(node.id().toString());
        int internalNodeId = nodeVocabulary.getId(getStrId(nodeId, false));
        return getDirectory(internalNodeId) + "/node_if" + internalNodeId;
    }

    private static String getDirectory(int id) {
        String directory = INVERTED_FILES_DIRECTORY;
        for (int i = 2; i > 0; i--) {
            directory += "/" + (id / (int) Math.pow(1000, i));
        }
        FileUtils.createDirectories(directory);
        return directory;
    }

    public static String getStrId(int id, boolean isDataVector) {
        return isDataVector ? "d" + id : "n" + id;
    }

    public static boolean isDataVector(String strId) {
        return strId.startsWith("d");
    }

    public long getNumDistinctTerms() {
        return termVocabulary.size();
    }

    @Override
    public long getSizeInBytes() {
        try {
            updateSizes();
            return super.getSizeInBytes() + invertedFilesSizeInBytes;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public long getVectorsSizeInBytes() throws ColumnFileException, DataNotFoundException, IOException {
        return vectorCache.getStoredSizeInBytes();
    }

    private void updateSizes() {
        if (lastTreeSizeInBytes != super.getSizeInBytes()) {
            lastTreeSizeInBytes = super.getSizeInBytes();
            invertedFilesSizeInBytes = computeInvertedFilesSizeInBytes((IndexEntry) this.rootEntry);
        }
    }

    private long computeInvertedFilesSizeInBytes(IndexEntry root) {
        long size = 0;
        try {
            Stack<IndexEntry> stack = new Stack<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                IndexEntry n = stack.pop();

                InvertedFile invertedFile = new InvertedFile(null, getNodeInvFile(n));
                invertedFile.open();

                size += invertedFile.getSizeInBytes();

                invertedFile.close();

                if (n.level() != 0) {
                    for (Iterator it = n.get().entries(); it.hasNext();) {
                        stack.push((IndexEntry) it.next());
                    }
                }
            }
        } catch (SSEExeption e) {
            throw new RuntimeException(e);
        }
        return size;
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        try {
            vectorCache.flush();
        } catch (ColumnFileException | SSEExeption ex) {
            throw new IOException(ex);
        }
    }

    /**
     *
     * @param root
     * @throws SSEExeption
     * @throws IOException
     */
    public void buildInvertedFiles(IndexEntry root) throws SSEExeption, IOException, ColumnFileException, InterruptedException {
        HashMap<Integer, List<PostingListEntry>> invertedFile;
        IndexEntry node;
        CacheItem<IndexEntry> entry;

        Stack<CacheItem<IndexEntry>> stack = new Stack<>();
        stack.push(new CacheItem<>(root, CacheItem.Type.READ));

        int id;
        while (!stack.isEmpty()) {
            entry = stack.peek();
            node = entry.getItem();
            invertedFile = new HashMap<>();
            if (entry.isRead()) {
                if (node.level() == 0) { //leafNode
                    stack.pop();
                    KPE child;
                    for (Iterator it = node.get().entries(); it.hasNext();) {
                        child = (KPE) it.next();
                        id = Integer.parseInt(child.getID().toString());
                        union(invertedFile, getDataVector(id), id);
                    }
                    updateInvertedFile(node, invertedFile);
                } else {
                    entry.setType(CacheItem.Type.WRITE);//mark the entry as visited
                    for (Iterator it = node.get().entries(); it.hasNext();) {
                        stack.push(new CacheItem<>((IndexEntry) it.next(), CacheItem.Type.READ));
                    }
                }
            } else {
                stack.pop();
                IndexEntry child;
                for (Iterator it = node.get().entries(); it.hasNext();) {
                    child = (IndexEntry) it.next();
                    id = Integer.parseInt(child.id().toString());
                    union(invertedFile, getNodeVector(id), id);
                }

                updateInvertedFile(node, invertedFile);
            }
        }
    }

    private void updateInvertedFile(final IndexEntry n,
            final HashMap<Integer, List<PostingListEntry>> newInvertedFile) {
        try {
            InvertedFile nodeInvertedFile;
            nodeInvertedFile = new InvertedFile(null, getNodeInvFile(n));
            nodeInvertedFile.open();

            for (Entry<Integer, List<PostingListEntry>> entry : newInvertedFile.entrySet()) {
                nodeInvertedFile.putPostingList(entry.getKey(), entry.getValue());
            }
            nodeInvertedFile.close();
        } catch (SSEExeption ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void checkTree(IndexEntry root) {
        try {
            Stack<CacheItem<IndexEntry>> stack = new Stack<>();
            stack.push(new CacheItem<>(root, CacheItem.Type.READ));
            while (!stack.isEmpty()) {
                CacheItem<IndexEntry> entry = stack.peek();
                IndexEntry n = entry.getItem();

                if (entry.isRead()) {
                    if (n.level() == 0) { //leafNode
                        stack.pop();
                        Vector vector = new Vector();
                        KPE child;
                        for (Iterator it = n.get().entries(); it.hasNext();) {
                            child = (KPE) it.next();
                            vector.agg(getDataVector(Integer.parseInt(child.getID().toString())));
                        }
                        if (!vector.equals(getNodeVector(Integer.parseInt(n.id().toString())))) {
                            throw new RuntimeException(" E R R O R ! -> Checking IRRTree. Leaf Node_id=" + n.id().toString());
                        }
                    } else {
                        entry.setType(CacheItem.Type.WRITE);//mark the entry as visited
                        for (Iterator it = n.get().entries(); it.hasNext();) {
                            stack.push(new CacheItem<>((IndexEntry) it.next(), CacheItem.Type.READ));
                        }
                    }
                } else {
                    Vector vector = new Vector();
                    stack.pop();
                    IndexEntry child;
                    for (Iterator it = n.get().entries(); it.hasNext();) {
                        child = (IndexEntry) it.next();
                        vector.agg(getNodeVector(Integer.parseInt(child.id().toString())));
                    }
                    if (!vector.equals(getNodeVector(Integer.parseInt(n.id().toString())))) {
                        throw new RuntimeException(" E R R O R ! -> Checking IRRTree. Node_id=" + n.id().toString());
                    }
                }
            }
        } catch (IOException | ColumnFileException | SSEExeption | RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString(IndexEntry n, int level) {
        StringBuilder str = new StringBuilder();
        StringBuffer space = new StringBuffer();
        for (int i = n.level(); i < (this.height() - 1); i++) {
            space.append("     ");
        }
        str.append(space);
        str.append(toString((DoublePointRectangle) n.descriptor()));
        str.append("node_id=").append(n.id());

        try {
            InvertedFile invertedFile = new InvertedFile(null,
                    ((IRTree) this).getNodeInvFile(n));

            invertedFile.open();
            str.append(", IF={").append(invertedFile.toString()).append("}");
            invertedFile.close();

        } catch (SSEExeption ex) {
            throw new RuntimeException(ex);
        }

        str.append('\n');

        space.append("     ");
        if (n.level() == 0) {
            for (Iterator it = n.get().entries(); it.hasNext();) {
                str.append(space);
                KPE dataEntry = (KPE) it.next();
                str.append(dataEntry.getID());
                str.append(toString((DoublePointRectangle) dataEntry.getData()));

                try {
                    str.append(((IRTree) this).vectorCache.getDataVector(Integer.parseInt(dataEntry.getID().toString())));
                } catch (NumberFormatException | IOException | ColumnFileException | SSEExeption ex) {
                    throw new RuntimeException(ex);
                }

                str.append('\n');
            }
        } else {
            if ((n.level() - 1) >= level) {
                for (Iterator it = n.get().entries(); it.hasNext();) {
                    str.append(toString((IndexEntry) it.next(), level));
                }
            }
        }
        return str.toString();

    }

    public static void main(String[] args) throws Exception {
        java.util.Properties properties = new java.util.Properties();
        properties.load(new java.io.FileInputStream("spatial.properties"));

        int bufferSize = 0;
        int vectorCacheSize = 100;

        // internal variables
        // Leafnodes are 32+4 Bytes of size.
        // Indexnodes are 32+8 Bytes of size.
        // so take the maximum for the block size!
        //int blockSize = 4+2+(32+8)*maxcap;
        IRTree.INVERTED_FILES_DIRECTORY = properties.getProperty("irTree.folder") + "/ifDir";
        DefaultStatisticCenter statisticCenter = new DefaultStatisticCenter();
        IRTree irTree = new IRTree(statisticCenter, "ir_tree_id_",
                properties.getProperty("irTree.folder") + "/rtree",
                Integer.parseInt(properties.getProperty("irTree.dimensions")),
                bufferSize,
                Integer.parseInt(properties.getProperty("diskStorage.blockSize")),
                Integer.parseInt(properties.getProperty("irTree.minNodeEntries")),
                Integer.parseInt(properties.getProperty("irTree.maxNodeEntries")),
                FileUtils.DISK_PAGE_SIZE, FileUtils.DISK_PAGE_SIZE, vectorCacheSize, true);


        irTree.open();
        BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream("small.txt")));

        long time = System.currentTimeMillis();
        String line = input.readLine();
        if (irTree.getSizeInBytes() == 0) {
            while (line != null) {
                Item item = parse(line);
                //System.out.println(item);
                TextRectangle mbr = new TextRectangle(new double[]{item.lat, item.lgt},
                        new double[]{item.lat, item.lgt}, item.id, item.txt);
                //DoublePointRectangleMax mbr = new DoublePointRectangleMax(point, point, RandomUtil.nextDouble(1));
                irTree.insert(new KPE(mbr, item.id, IntegerConverter.DEFAULT_INSTANCE));
                line = input.readLine();
            }

            irTree.buildInvertedFiles((IndexEntry) irTree.rootEntry());
            irTree.flush();
        }


       //System.out.println("\n\n\nTree:\n" + irTree.toString());

        System.out.println("IRTree constructed in " + Util.time(System.currentTimeMillis() - time));
        System.out.println("Statistics results...");
        System.out.println(statisticCenter.getStatus());

        System.out.print("Checking the tree...");
        irTree.checkTree((IndexEntry) irTree.rootEntry());
        System.out.println("OK!");

        input.close();
        irTree.close();
    }

    public static Item parse(String line) throws ParseException {

        String[] split = line.split(" ");

        int id = Integer.parseInt(split[0]);
        String user = split[1];
        double lat = Double.parseDouble(split[2]);
        double lng = Double.parseDouble(split[3]);

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd" + "-" + "HH:mm:ss");
        Date timestamp = formatter.parse(split[4] + "-" + split[5]);

        String text = "";

        for (int i = 7; i < split.length; i++) {
            text += split[i] + " ";
        }



        return new Item(id, line, lat, lng, timestamp, text);
    }
}

class Item {

    int id;
    String screenName;
    double lat;
    double lgt;
    Date createdAt;
    String txt;

    public Item(int id, String screenName, double lat, double lgt, Date createdAt, String txt) {
        this.id = id;
        this.screenName = screenName;
        this.lat = lat;
        this.lgt = lgt;
        this.createdAt = createdAt;
        this.txt = txt;
    }

    @Override
    public String toString() {
        return "[id:" + id + ", screen name: " + screenName + ",(" + lat + "," + lgt + "), created at: " + createdAt + ", text: " + txt + "]";
    }
}
