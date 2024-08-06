package com.hy.entity;

import lombok.Data;

import java.util.List;

@Data
public class ReplyList {

    private Data1 data;

    @Data
    public static class Data1 {
        private List<Reply> replies;
        private Cursor cursor;
        @Data
        public static class Cursor {
            private boolean is_begin;
            private int prev;
            private int next;
            private boolean is_end;
            private int mode;
            private String mode_text;
            private int all_count;
            private List<Integer> support_mode;
            private String name;
            private PaginationReply pagination_reply;
            private String session_id;

            // getters and setters

            @Data
            public static class PaginationReply {
                private String next_offset;

                // getters and setters

                /*@Data
                public static class Offset {
                    @SerializedName("type")
                    private int type;
                    @SerializedName("direction")
                    private int direction;
                    @SerializedName("data")
                    private DataCursor data;

                    // getters and setters

                    @Data
                    public static class DataCursor {
                        @SerializedName("cursor")
                        private int cursor;

                        // getters and setters
                    }
                }*/
            }
        }
    }

    @Data
    public static class Reply {
        private long rpid;
        private long oid;
        private int type;
        private long mid;
        private long root;
        private long parent;
        private long dialog;
        private int count;
        private int rcount;
        private int state;
        private int fansgrade;
        private long attr;
        private long ctime;
        private String mid_str;
        private String oid_str;
        private String rpid_str;
        private String root_str;
        private String parent_str;
        private String dialog_str;
        private int like;
        private int action;
        private Member member;
        private Content content;
        private List<Reply> replies;
        private int assist;
        private UpAction up_action;
        private boolean invisible;
        private ReplyControl reply_control;
        private Folder folder;
        private String dynamic_id_str;
        private String note_cvid_str;
        private String track_info;

        // Getters and setters omitted for brevity

        @Data
        public static class Member {
            private String mid;
            private String uname;
            private String sex;
            private String sign;
            private String avatar;
            private String rank;
            private int face_nft_new;
            private int is_senior_member;
            private Senior senior;
            private LevelInfo level_info;
            private Pendant pendant;
            private Nameplate nameplate;
            private OfficialVerify official_verify;
            private Vip vip;
            private Object fans_detail;
            private UserSailing user_sailing;
            private UserSailingV2 user_sailing_v2;
            private boolean is_contractor;
            private String contract_desc;
            private Object nft_interaction;
            private AvatarItem avatar_item;


            // Getters and setters omitted for brevity

            public static class Senior {
                // This class intentionally left empty
            }

            @Data
            public static class LevelInfo {
                private int current_level;
                private int current_min;
                private int current_exp;
                private int next_exp;

                // Getters and setters omitted for brevity
            }

            @Data
            public static class Pendant {
                private int pid;
                private String name;
                private String image;
                private int expire;
                private String image_enhance;
                private String image_enhance_frame;
                private long n_pid;

                // Getters and setters omitted for brevity
            }

            @Data
            public static class Nameplate {
                private int nid;
                private String name;
                private String image;
                private String image_small;
                private String level;
                private String condition;

                // Getters and setters omitted for brevity
            }

            @Data
            public static class OfficialVerify {
                private int type;
                private String desc;

                // Getters and setters omitted for brevity
            }

            @Data
            public static class Vip {
                private int vipType;
                private long vipDueDate;
                private String dueRemark;
                private int accessStatus;
                private int vipStatus;
                private String vipStatusWarn;
                private int themeType;
                private Label label;
                private int avatar_subscript;
                private String nickname_color;

                // Getters and setters omitted for brevity

                @Data
                public static class Label {
                    private String path;
                    private String text;
                    private String label_theme;
                    private String text_color;
                    private int bg_style;
                    private String bg_color;
                    private String border_color;
                    private boolean use_img_label;
                    private String img_label_uri_hans;
                    private String img_label_uri_hant;
                    private String img_label_uri_hans_static;
                    private String img_label_uri_hant_static;

                    // Getters and setters omitted for brevity
                }
            }

            @Data
            public static class UserSailing {
                private Object pendant;
                private Object cardbg;
                private Object cardbg_with_focus;

                // Getters and setters omitted for brevity
            }

            public static class UserSailingV2 {
                // This class intentionally left empty
            }

            @Data
            public static class AvatarItem {
                private ContainerSize container_size;
                private FallbackLayers fallback_layers;
                private String mid;

                // Getters and setters omitted for brevity

                @Data
                public static class ContainerSize {
                    private double width;
                    private double height;

                    // Getters and setters omitted for brevity
                }

                @Data
                public static class FallbackLayers {
                    private List<Layer> layers;
                    private boolean is_critical_group;

                    // Getters and setters omitted for brevity

                    @Data
                    public static class Layer {
                        private boolean visible;
                        private GeneralSpec general_spec;
                        private LayerConfig layer_config;
                        private Resource resource;

                        // Getters and setters omitted for brevity

                        @Data
                        public static class GeneralSpec {
                            private PosSpec pos_spec;
                            private SizeSpec size_spec;
                            private RenderSpec render_spec;

                            // Getters and setters omitted for brevity

                            @Data
                            public static class PosSpec {
                                private int coordinate_pos;
                                private double axis_x;
                                private double axis_y;

                                // Getters and setters omitted for brevity
                            }

                            @Data
                            public static class SizeSpec {
                                private double width;
                                private double height;

                                // Getters and setters omitted for brevity
                            }

                            @Data
                            public static class RenderSpec {
                                private double opacity;

                                // Getters and setters omitted for brevity
                            }
                        }

                        @Data
                        public static class LayerConfig {
                            private Tags tags;
                            private boolean is_critical;

                            // Getters and setters omitted for brevity

                            @Data
                            public static class Tags {
                                private AvatarLayer AVATAR_LAYER;
                                private GeneralCfg GENERAL_CFG;

                                // Getters and setters omitted for brevity

                                public static class AvatarLayer {
                                    // This class intentionally left empty
                                }

                                @Data
                                public static class GeneralCfg {
                                    private int config_type;
                                    private GeneralConfig general_config;

                                    // Getters and setters omitted for brevity

                                    @Data
                                    public static class GeneralConfig {
                                        private WebCssStyle web_css_style;

                                        // Getters and setters omitted for brevity

                                        public static class WebCssStyle {
                                            private String borderRadius;

                                            // Getters and setters omitted for brevity
                                        }
                                    }
                                }
                            }
                        }

                        @Data
                        public static class Resource {
                            private int res_type;
                            private ResImage res_image;

                            // Getters and setters omitted for brevity

                            @Data
                            public static class ResImage {
                                private ImageSrc image_src;

                                // Getters and setters omitted for brevity

                                @Data
                                public static class ImageSrc {
                                    private int src_type;
                                    private int placeholder;
                                    private Remote remote;

                                    // Getters and setters omitted for brevity

                                    @Data
                                    public static class Remote {
                                        private String url;
                                        private String bfs_style;

                                        // Getters and setters omitted for brevity
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        @Data
        public static class Content {
            private String message;
            private List<Object> members;
            private JumpUrl jump_url;
            private int max_line;

            // Getters and setters omitted for brevity

            public static class JumpUrl {
                // This class intentionally left empty
            }
        }

        @Data
        public static class UpAction {
            private boolean like;
            private boolean reply;

            // Getters and setters omitted for brevity
        }

        @Data
        public static class ReplyControl {
            private int max_line;
            private String time_desc;
            private String location;

            // Getters and setters omitted for brevity
        }

        @Data
        public static class Folder {
            private boolean has_folded;
            private boolean is_folded;
            private String rule;

            // Getters and setters omitted for brevity
        }
    }
}
