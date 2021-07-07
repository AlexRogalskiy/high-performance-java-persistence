package com.vladmihalcea.book.hpjp.hibernate.criteria.blaze;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.JoinType;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import com.vladmihalcea.book.hpjp.hibernate.association.AllAssociationTest;
import com.vladmihalcea.book.hpjp.util.AbstractOracleIntegrationTest;
import org.junit.Test;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * @author Georgeta Mihalcea
 */
public class BlazePersistenceLateralJoinTest extends AbstractOracleIntegrationTest {

    private CriteriaBuilderFactory cbf;

    @Override
    protected Class<?>[] entities() {
        return new Class<?>[]{
            Post.class,
            PostDetails.class,
            PostComment.class,
            Tag.class
        };
    }

    @Override
    protected EntityManagerFactory newEntityManagerFactory() {
        EntityManagerFactory entityManagerFactory = super.newEntityManagerFactory();
        CriteriaBuilderConfiguration config = Criteria.getDefault();
        cbf = config.createCriteriaBuilderFactory(entityManagerFactory);
        return entityManagerFactory;
    }

    @Override
    public void afterInit() {
        doInJPA(entityManager -> {
            Post post = new Post()
                .setId(1L)
                .setTitle("High-Performance Java Persistence")
                .addComment(
                    new PostComment()
                        .setReview("Best book on JPA and Hibernate!")
                )
                .addComment(
                    new PostComment()
                        .setReview("A must-read for every Java developer!")
                );

            entityManager.persist(post);

            entityManager.persist(
                new PostDetails()
                    .setPost(post)
                    .setCreatedBy("Vlad Mihalcea")
            );

            Tag java = new Tag().setName("Java");
            Tag hibernate = new Tag().setName("Hibernate");

            entityManager.persist(java);
            entityManager.persist(hibernate);

            post.getTags().add(java);
            post.getTags().add(hibernate);
        });
    }

    @Test
    public void testLateralJoinBlaze() {
        doInJPA(entityManager -> {
            List<Tuple> tuples = entityManager
                .createNativeQuery("""
                 select 
                    p.id as post_id, 
                    p.title as post_title,
                    pc.review as latest_comment_review
                 from post p,
                 lateral (
                     select *
                     from post_comment pc1
                     where pc1.id = (
                         select max(pc2.id)
                         from post_comment pc2
                     )
                 ) pc
			    """, Tuple.class)
                .getResultList();

            assertEquals(1, tuples.size());
        });

        doInJPA(entityManager -> {
            List<Tuple> tuples = cbf
                .create(entityManager, Tuple.class)
                .from(Post.class, "p")
                .leftJoinLateralEntitySubquery(PostComment.class, "pc1", "pc")
                    .where("id").in()
                        .from(PostComment.class, "pc2")
                        .select("max(pc2.id)")
                    .end()
                .end()
                .select("p.id", "post_id")
                .select("p.title", "post_title")
                .select("pc1.review", "latest_comment_review")
                .getResultList();

            assertEquals(1, tuples.size());
        });
    }

    @Test
    public void testGroupBy() {
        doInJPA(entityManager -> {
            List<Tuple> tuples = entityManager
                .createNativeQuery("""
                 select
                     p.title as post_title,
                     count(pc.id) as comment_count
                 from post p
                 left join post_comment pc on pc.post_id = p.id
                 join post_details pd on p.id = pd.id
                 where pd.created_by = :createdBy
                 group by p.title
			    """, Tuple.class)
                .setParameter("createdBy", "Vlad Mihalcea")
                .getResultList();

            assertEquals(1, tuples.size());
        });

        doInJPA(entityManager -> {
            List<Tuple> tuples = cbf
                .create(entityManager, Tuple.class)
                .from(Post.class, "p")
                .leftJoinOn(PostComment.class, "pc").onExpression("pc.post = p").end()
                .joinOn(PostDetails.class, "pd", JoinType.INNER).onExpression("pd = p").end()
                .where("pd.createdBy").eqExpression(":createdBy")
                .groupBy("p.title")
                .select("p.title", "post_title")
                .select("count(pc.id)", "comment_count")
                .setParameter("createdBy", "Vlad Mihalcea")
                .getResultList();

            assertEquals(1, tuples.size());
        });
    }

    @Test
    public void testJoinGroupBy() {
        doInJPA(entityManager -> {
            List<Tuple> tuples = entityManager
                .createNativeQuery("""
                 select 
                    p1.title,
                    p_c.comment_count
                 from post p1
                 join (
                    select
                         p.title as post_title,
                         count(pc.id) as comment_count
                     from post p
                     left join post_comment pc on pc.post_id = p.id
                     join post_details pd on p.id = pd.id
                     where pd.created_by = :createdBy
                     group by p.title
                 ) p_c on p1.title = p_c.post_title
			    """, Tuple.class)
                .setParameter("createdBy", "Vlad Mihalcea")
                .getResultList();

            assertEquals(1, tuples.size());
        });

        doInJPA(entityManager -> {
            List<Tuple> tuples = cbf
                .create(entityManager, Tuple.class)
                .from(Post.class, "p1")
                .leftJoinLateralSubquery(Post.class, "p_c")
                    .from(Post.class, "p")
                    .leftJoinOn(PostComment.class, "pc").onExpression("pc.post = p").end()
                    .joinOn(PostDetails.class, "pd", JoinType.INNER).onExpression("pd = p").end()
                    .where("pd.createdBy").eqExpression(":createdBy")
                    .whereExpression("p.title = p1.title")
                    .groupBy("p.title")
                .end()
                .select("p.title", "post_title")
                .select("count(pc.id)", "comment_count")
                .setParameter("createdBy", "Vlad Mihalcea")
                .getResultList();

            assertEquals(1, tuples.size());
        });
    }

    @Entity(name = "Post")
    @Table(name = "post")
    public static class Post {

        @Id
        private Long id;

        private String title;

        @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
        private List<PostComment> comments = new ArrayList<>();

        @OneToOne(cascade = CascadeType.ALL, mappedBy = "post",
            orphanRemoval = true, fetch = FetchType.LAZY)
        private PostDetails details;

        @ManyToMany
        @JoinTable(name = "post_tag",
            joinColumns = @JoinColumn(name = "post_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
        )
        private List<Tag> tags = new ArrayList<>();

        public Long getId() {
            return id;
        }

        public Post setId(Long id) {
            this.id = id;
            return this;
        }

        public String getTitle() {
            return title;
        }

        public Post setTitle(String title) {
            this.title = title;
            return this;
        }

        public List<PostComment> getComments() {
            return comments;
        }

        private Post setComments(List<PostComment> comments) {
            this.comments = comments;
            return this;
        }

        public Post addComment(PostComment comment) {
            comments.add(comment);
            comment.setPost(this);

            return this;
        }

        public Post removeComment(PostComment comment) {
            comments.remove(comment);
            comment.setPost(null);

            return this;
        }

        public Post addDetails(PostDetails details) {
            this.details = details;
            details.setPost(this);

            return this;
        }

        public Post removeDetails() {
            this.details.setPost(null);
            this.details = null;

            return this;
        }

        public List<Tag> getTags() {
            return tags;
        }
    }

    @Entity(name = "PostComment")
    @Table(name = "post_comment")
    public static class PostComment {

        @Id
        @GeneratedValue
        private Long id;

        private String review;

        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "post_id")
        private Post post;

        public PostComment() {
        }

        public PostComment(String review) {
            this.review = review;
        }

        public Long getId() {
            return id;
        }

        public PostComment setId(Long id) {
            this.id = id;
            return this;
        }

        public String getReview() {
            return review;
        }

        public PostComment setReview(String review) {
            this.review = review;
            return this;
        }

        public Post getPost() {
            return post;
        }

        public PostComment setPost(Post post) {
            this.post = post;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PostComment)) return false;
            return id != null && id.equals(((PostComment) o).getId());
        }

        @Override
        public int hashCode() {
            return getClass().hashCode();
        }
    }

    @Entity(name = "PostDetails")
    @Table(name = "post_details")
    public static class PostDetails {

        @Id
        private Long id;

        @Column(name = "created_on")
        private Date createdOn;

        @Column(name = "created_by")
        private String createdBy;

        public PostDetails() {
            createdOn = new Date();
        }

        @OneToOne(fetch = FetchType.LAZY)
        @MapsId
        @JoinColumn(name = "id")
        private Post post;

        public Long getId() {
            return id;
        }

        public PostDetails setId(Long id) {
            this.id = id;
            return this;
        }

        public Post getPost() {
            return post;
        }

        public PostDetails setPost(Post post) {
            this.post = post;
            return this;
        }

        public Date getCreatedOn() {
            return createdOn;
        }

        public PostDetails setCreatedOn(Date createdOn) {
            this.createdOn = createdOn;
            return this;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public PostDetails setCreatedBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }
    }

    @Entity(name = "Tag")
    @Table(name = "tag")
    public static class Tag {

        @Id
        @GeneratedValue
        private Long id;

        private String name;

        public String getName() {
            return name;
        }

        public Tag setName(String name) {
            this.name = name;
            return this;
        }
    }
}
