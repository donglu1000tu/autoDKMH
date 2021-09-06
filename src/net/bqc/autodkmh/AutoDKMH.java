package net.bqc.autodkmh;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Tool for automatically registering courses of VNU.
 *
 * @Created by cuong on  2/12/2015
 * @Updated by cuong on 17/08/2017
 */
public class AutoDKMH {
    /**
     * Địa chỉ máy chủ đăng ký, có 2 máy chủ, có thể chuyển sang máy chủ phụ khi cần thiết
     */
    public final static String HOST = "http://dangkyhoc.vnu.edu.vn";

    /**
     * Path tới API đăng nhập (mình tạm gọi là API)
     */
    public final static String LOGIN_URL = HOST + "/dang-nhap";

    /**
     * Path tới API đăng xuất
     */
    public final static String LOGOUT_URL = HOST + "/Account/Logout";

    /**
     * Path tới API lấy dữ liệu danh sách môn học chuyên ngành
     */
    public final static String AVAILABLE_COURSES_DATA_URL_MAJOR = HOST + "/danh-sach-mon-hoc/1/1";

    /**
     * Path tới API lấy dữ liệu danh sách môn học toàn trường
     */
    public final static String AVAILABLE_COURSES_DATA_URL_ALL = HOST + "/danh-sach-mon-hoc/1/2";

    /**
     * Path tới API lấy dữ liệu môn học đã đăng ký
     */
    public final static String REGISTERED_COURSES_DATA_URL = HOST + "/danh-sach-mon-hoc-da-dang-ky/1";

    /**
     * Path tới API kiểm tra điều kiện tiên quyết, "%s" sẽ được thay thế bởi "data-crdid" của môn học
     */
    public final static String CHECK_PREREQUISITE_COURSES_URL = HOST + "/kiem-tra-tien-quyet/%s/1";

    /**
     * Path tới API chọn môn học đăng ký, "%s" sẽ được thay thế bởi "data-rowindex" của môn học
     */
    public final static String CHOOSE_COURSE_URL = HOST + "/chon-mon-hoc/%s/1/1";

    /**
     * Path tới API ghi nhận đăng ký
     */
    public final static String SUBMIT_URL = HOST + "/xac-nhan-dang-ky/1";


    public final static String USER_AGENT = "Mozilla/5.0";
    public final static String ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    public final static String ACCEPT_LANGUAGE = "en-US,en;q=0.5";

    private HttpURLConnection con;

    /**
     * Tên tài khoản
     */
    private String user;

    /**
     * Mật khẩu tài khoản
     */
    private String password;

    /**
     * Danh sách mã môn học cần đăng ký
     */
    private List<String> courseCodes;


    private List<Course> courses;

    private long sleepTime;

    /**
     * Phương thức khởi tạo, làm nhiệm vụ load thông tin cấu hình cho tool
     */
    public AutoDKMH() {
        courseCodes = new ArrayList<>();
        courses = new ArrayList<>();
        loadInitialParameters("config.properties");
    }

    /**
     * Phương thức main
     */
    public static void main(String args[]) throws IOException, InterruptedException {
        AutoDKMH tool = new AutoDKMH();

        // tool.sendGet(HOST);

        logn("/******************************************/");
        logn("//! Username = " + tool.user);
        // not support for password under 2 characters :P
        logn("//! Password = " + "********");
        logn("//! Course Codes = " + tool.courseCodes);
        logn("/******************************************/");

        tool.run();
    }

    /**
     * The entrance gate to dark world...
     *
     * @throws IOException
     * @throws InterruptedException
     */

    private void run() throws IOException, InterruptedException {
        // turn on cookie
        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault(cookieManager);
        Calendar cal = Calendar.getInstance();

        /*
         * Thực hiện các đợt đăng ký liên tiếp, chỉ dừng lại khi tất cả môn học đã được đăng ký
         */
        while (true) {
            logn("\n/******************************************/");
            logn("Try on: " + cal.getTime().toString());
            // đăng nhập bao giờ thành công thì mới thực hiện các bước tiếp theo
            try {
                doLogin();
            } catch (Exception e) {
                System.err.println("\nEncountered exception " + e.getMessage());
                logn("Try again...");
                continue;
            }

            // lọc và chỉ giữ lại những môn chưa được đăng ký trên hệ thống
            // get registered courses, filter desired courses
            // it is necessary to do it before submitting courses
            log("Filtering desired courses...");
            String registeredCoursesData = sendPost(REGISTERED_COURSES_DATA_URL, "");
            courseCodes = courseCodes.stream()
                    .filter(code -> !registeredCoursesData.contains(code))
                    .collect(Collectors.toList());
            logn("[Done]");
            logn("Filtered courses: " + courseCodes);

            /* trong trường hợp danh sách môn học sau khi lọc trống, có nghĩa rằng bạn đã đăng ký được tất cả
             * môn học mong muốn rồi. Lúc này tool ngừng hoạt động.
             */
            if (courseCodes.isEmpty()) {
                logn("\nCourses have been already registered!\n[Exit]");
                System.exit(1);
            }

            // lưu ý: bạn bắt buộc phải lấy dữ liệu danh sách môn học chuyên ngành thì hệ thống mới cho phép bạn đăng ký vào ghi nhận
            sendPost(AVAILABLE_COURSES_DATA_URL_MAJOR, "");


            log("Get raw courses data...");
            // lấy dữ liệu danh sách môn học toàn trường (luôn đầy đủ các môn học)
            String coursesData = sendPost(AVAILABLE_COURSES_DATA_URL_ALL, "");
            logn("[Done]");

            // thực hiện đăng ký các môn học theo các mã môn học từ tệp cấu hình
            for (Iterator<String> it = courseCodes.iterator(); it.hasNext(); ) {
                String courseCode = it.next();
                log("\nGetting course information for [" + courseCode + "]...");
                // lấy thông tin data-crdid và data-rowindex của môn học
                String courseDetails[] = getCourseDetailsFromCoursesData(coursesData, courseCode);
                logn("[Done]");

                /* register courses and submit them */
                if (courseDetails != null) {
                    // thực hiện kiểm tra điền kiện tiên quyết
                    log("Checking prerequisite courses...");
                    String res = sendPost(String.format(CHECK_PREREQUISITE_COURSES_URL, courseDetails[0]), "");
                    logn("[Done]");
                    logn("Response: " + res);

                    // thực hiện chọn môn học
                    log("Choose [" + courseCodes + "] for queue...");
                    res = sendPost(String.format(CHOOSE_COURSE_URL, courseDetails[1]), "");
                    logn("[Done]");
                    logn("Response: " + res);

                    // bỏ mã môn học ra danh sách cần đăng ký khi đã thành công
                    if (res.contains("thành công"))
                        it.remove();
                }
            }

            // thực hiện ghi nhận đăng ký
            log("Submitting...");
            String res = sendPost(String.format(SUBMIT_URL, ""), "");
            logn("[Done]");
            logn("Response: " + res);

            // đăng xuất khỏi hệ thống
            log("Logging out...");
            sendGet(LOGOUT_URL);
            logn("[Success]");

            // nếu đăng ký thành công tất cả môn học, thoát
            if (courseCodes.isEmpty()) {
                logn("\nRegistered all!\n[Exit]");
                System.exit(1);
            }

            logn("/******************************************/");
            // tạm nghỉ 2s để thực hiện đợt đăng ký tiếp theo (chưa đăng ký được hết các môn)
            Thread.sleep(sleepTime);
        }
    }

    /**
     * Load login site to get cookie and login parameters then login using post
     * method
     *
     * @throws IOException
     */
    /**
     * Thực hiện đăng nhập vào hệ thống.
     *
     * Gợi ý: Đầu tiên gửi GET request đến API đăng nhập để lấy được CSRF token,
     * sau đó gửi POST request đến API đăng nhập để thực hiện đăng nhập.
     * Các tham số cần gửi trong POST request gồm LoginName, Password, __RequestVerificationToken
     *
     * Lưu ý: Nhớ kích hoạt Cookie để có thể "giữ liên lạc" với máy chủ trong những request sau này
     */
    private void doLogin() throws IOException {
        log("Getting cookies, token...");
        String loginSiteHtml = sendGet(LOGIN_URL);
        logn("[Done]");

        log("Logging in...");
        String loginParams = getFormParams(loginSiteHtml, user, password);
        String res = sendPost(LOGIN_URL, loginParams);
        if (!res.contains("<title>Trang ch\u1EE7")) {
            logn("[Fail]");
            System.exit(1);
        }
        logn("[Success]");
    }

    /**
     * Load thông tin từ tệp cấu hình cấu hình (properties file).
     * Thông tin gồm tài khoản đăng nhập và danh sách mã môn học cần đăng ký.
     * Tên tài khoản gán vào thuộc tính username
     * Mật khẩu tài khoản gán vào thuộc tính password
     * Danh sách mã môn học cần đăng ký gán vào thuộc tính courseCodes
     *
     * @param   filePath
     *          đường dẫn đến tệp cấu hình
     */
    private void loadInitialParameters(String filePath) {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            InputStream is = loader.getResourceAsStream(filePath);
            Properties p = new Properties();
            p.load(is);

            this.user = p.getProperty("usr");
            this.password = p.getProperty("passwd");

            String rawCourseCodes = p.getProperty("course_codes");
            String[] courseCodesArr = rawCourseCodes.split("\\.");
            courseCodes.addAll(Arrays.asList(courseCodesArr));

            String sleepTimeStr = p.getProperty("sleep_time");
            this.sleepTime = Long.parseLong(sleepTimeStr);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Get data-crdid and data-rowindex of a course
     *
     * @param coursesDataHtml
     * @param courseCode      the given code of course
     * @return the first element is data-crdid and the second is data-rowindex
     * if the course is available. Otherwise, return null
     */
    /**
     * Lấy thông tin về data-crdid và data-rowindex của môn học muốn đăng ký.
     * Hai thông tin này được sử dụng khi gọi vào các API "kiểm tra điều kiện tiên quyết" và "chọn môn học đăng ký".
     *
     * Gợi ý: Sử dụng thư viện Jsoup để trích xuất các thông tin này từ dữ liệu HTML.
     *
     * @param   coursesDataHtml
     *          dữ liệu HTML thô (lấy được khi gửi yêu cầu tới các API lấy dữ liệu danh sách môn học),
     *          xem mẫu dữ liệu html thô này ở tệp data/data.html.zip
     *
     * @param   courseCode
     *          mã của môn học muốn đăng ký
     *
     * @return  Mảng String, trong đó phần tử đầu tiên là data-crdid, phần tử thứ hai là data-rowindex. Trong trường
     *          hợp không tìm thấy thông tin của môn học, trả về null.
     */
    private String[] getCourseDetailsFromCoursesData(String coursesDataHtml, String courseCode) {
        coursesDataHtml = "<table id=\"coursesData\">" + coursesDataHtml + "</table>";
        Document doc = Jsoup.parse(coursesDataHtml);
        Elements elements = doc.select("#coursesData").select("tr");

        /* find course on courses list which owns the given course code */
        for (Element e : elements) {
            if (e.toString().contains(courseCode)) {
                /*
                 * data-cridid and data-rowindex always are at the first input
                 * tag if the course is available
                 */
                Element inputElement = e.getElementsByTag("input").get(0);

                if (inputElement.hasAttr("data-rowindex")) { // the course is
                    // available for
                    // registering
                    String crdid = inputElement.attr("data-crdid");
                    String rowindex = inputElement.attr("data-rowindex");
                    return new String[]{crdid, rowindex};
                }
            }
        }

        return null; // the course is not available
    }

    /**
     * Get parameters for login action
     *
     * @param html   parse to get cookie and parameters from this
     * @param user   user value parameter
     * @param passwd password value parameter
     * @return all parameters in a string
     */

    private String getFormParams(String html, String user, String passwd) {
        Document doc = Jsoup.parse(html);
        List<String> params = new ArrayList<>();

        // login form
        Elements elements = doc.getAllElements();
        Element loginForm = elements.first();
        Elements inputElements = loginForm.getElementsByTag("input");

        // generate parameters
        for (Element inputElement : inputElements) {
            String key = inputElement.attr("name");
            String value = inputElement.attr("value");

            if (key.equals("LoginName")) {
                value = user;
            } else if (key.equals("Password")) {
                value = passwd;
            }

            params.add(key + "=" + value);
        }

        StringBuilder builder = new StringBuilder();
        for (String param : params) {
            if (builder.length() == 0) {
                builder.append(param);

            } else
                builder.append("&").append(param);
        }

        return builder.toString();
    }

    /**
     * Send post method
     *
     * @param urlStr     url for post
     * @param postParams parameters
     * @return response from server
     * @throws IOException
     */
    /**
     * Thực hiện POST request
     *
     * @param   urlStr
     *          URL mong muốn gửi POST request đến
     * @param   postParams
     *          các tham số muốn đính kèm theo request, ngăn cách nhau bởi kí tự '&'
     *
     * @return  Kết quả trả về từ máy chủ, biểu diễn ở dạng String
     */
    private String sendPost(String urlStr, String postParams) throws IOException {
        URL url = new URL(urlStr);
        con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setUseCaches(false);
        con.setDoOutput(true);

        // set properties
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", ACCEPT);
        con.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);
        con.setRequestProperty("Connection", "keep-alive");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setRequestProperty("Content-Length", Integer.toString(postParams.length()));

        // Send post request
        DataOutputStream wr = new DataOutputStream(con.getOutputStream());
        wr.writeBytes(postParams);
        wr.flush();
        wr.close();

        // check result code
         int responseCode = con.getResponseCode();
         logn("\nSending 'POST' request to URL : " + url);
         logn("Post parameters : " + postParams);
         logn("Response Code : " + responseCode);

        // get content
        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
        String inputLine;
        StringBuffer response = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        return response.toString();
    }

    /**
     * Send get method
     *
     * @param urlStr url for get
     * @return response from server
     * @throws IOException
     */
    /**
     * Thực hiện GET request
     *
     * @param   urlStr
     *          URL mong muốn gửi GET request đến
     *
     * @return  Kết quả trả về từ máy chủ, biểu diễn ở dạng String
     */
    private String sendGet(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.setUseCaches(false);

        // set properties
        con.setRequestProperty("User-Agent", USER_AGENT);
        con.setRequestProperty("Accept", ACCEPT);
        con.setRequestProperty("Accept-Language", ACCEPT_LANGUAGE);

        // check result code
         int responseCode = con.getResponseCode();
         logn("\nSending 'GET' request to URL : " + url);
         logn("Response Code : " + responseCode);
        // get result content

        BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));

        String inputLine;
        StringBuffer response = new StringBuffer();
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        return response.toString();
    }

    /**
     * Ghi log và tạo dòng mới
     * @param   message
     *          nội dung để ghi log
     */
    private static void log(String message) {
        System.out.print(message);
    }

    private static void logn(String message) {
        log(message + "\n");
    }
}