"""Draw the user-approved local PNG alternative. No network or image API.

Facts: ../userinfoservices-guide-2026-09-23.md (inspected source, not speculation).
Only writes this task-owned output folder; does not modify the application.
"""
from pathlib import Path
import math
import random
import json
import zipfile
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent
W, MAX_H = 3000, 12500
PAPER = '#fffef8'
INK = {'blue': '#19549a', 'green': '#17634c', 'purple': '#724294',
       'red': '#a32b34', 'black': '#293444', 'grey': '#66717a'}
FILL = {'blue': '#f3f8ff', 'green': '#f0f9f3', 'purple': '#f8f2fc',
        'red': '#fff2ef', 'black': '#faf9f1', 'grey': '#faf9f1'}
FONT_DIR = Path('C:/Windows/Fonts')
HAND = FONT_DIR / 'segoepr.ttf'
BOLD = FONT_DIR / 'segoeprb.ttf'
CODE = FONT_DIR / 'consola.ttf'
L, GAP, CW = 170, 100, 1280
R = L + CW + GAP
INNER = 2 * CW + GAP

def font(size=38, kind='hand'):
    return ImageFont.truetype(str({'hand': HAND, 'bold': BOLD, 'code': CODE}[kind]), size)

class Sheet:
    def __init__(self, number, note):
        self.number = number
        self.rng = random.Random(720 + number)
        self.im = Image.new('RGB', (W, MAX_H), PAPER)
        self.d = ImageDraw.Draw(self.im)
        self.records = []
        self.boxes = []
        for yy in range(32, MAX_H, 44):
            for xx in range(38, W, 44):
                self.d.ellipse((xx, yy, xx+2, yy+2), fill='#dce4e3')
        self.d.line((112, 0, 112, MAX_H), fill='#edd0d0', width=3)
        self.d.ellipse((160, 45, 250, 135), outline=INK['blue'], width=4)
        self.d.text((183, 54), str(number), font=font(48, 'bold'), fill=INK['blue'])
        self.text(290, 58, note, 2510, size=36, color='black')
        x = 290
        for label, color in [('files / flow', 'blue'), ('database / result', 'green'),
                             ('settings / access', 'purple'), ('watch out', 'red')]:
            self.d.line((x, 143, x+42, 145), fill=INK[color], width=7)
            self.d.text((x+56, 119), label, font=font(28), fill=INK[color])
            x += 625
        self.y = 210

    def wrap(self, value, width, f):
        all_lines = []
        for paragraph in value.split('\n'):
            if not paragraph:
                all_lines.append('')
                continue
            current = ''
            for word in paragraph.split():
                trial = word if not current else current + ' ' + word
                if self.d.textlength(trial, font=f) <= width:
                    current = trial
                else:
                    if current:
                        all_lines.append(current)
                    current = word
                    while self.d.textlength(current, font=f) > width:
                        count = len(current)
                        while self.d.textlength(current[:count], font=f) > width:
                            count -= 1
                        all_lines.append(current[:count])
                        current = current[count:]
            if current:
                all_lines.append(current)
        return all_lines

    def text(self, x, y, value, width, size=38, color='black', kind='hand', record=True):
        f = font(size, kind)
        lh = int(size * 1.48)
        lines = self.wrap(value, width, f)
        for i, line in enumerate(lines):
            self.d.text((x, y+i*lh), line, font=f, fill=INK[color])
        if record:
            self.records.append({'text': value, 'x': x, 'y': y, 'width': width,
                                 'height': len(lines)*lh, 'size': size, 'lines': len(lines)})
        return y + len(lines)*lh

    def measure(self, title, notes, width, size=38):
        h = 52
        if title:
            h += len(self.wrap(title, width-76, font(40, 'code'))) * 60 + 20
        for note in notes:
            text, _, kind = note if isinstance(note, tuple) else (note, 'black', 'hand')
            h += len(self.wrap(text, width-76, font(size, kind))) * int(size*1.48) + 10
        return h + 24

    def rough_line(self, pts, color='blue', width=4):
        self.d.line(pts, fill=INK[color], width=width, joint='curve')
        jitter = [(x+self.rng.randint(-2,2), y+self.rng.randint(-2,2)) for x,y in pts]
        self.d.line(jitter, fill=INK[color], width=1, joint='curve')

    def card(self, x, y, width, title, notes, color='blue', size=38, minheight=0):
        h = max(minheight, self.measure(title, notes, width, size))
        assert y+h < MAX_H-160, (title, y, h)
        self.d.rounded_rectangle((x, y, x+width, y+h), radius=23, fill=FILL[color])
        pts=[(x+15,y+2),(x+width-17,y-1),(x+width+1,y+19),
             (x+width-2,y+h-15),(x+width-16,y+h+1),(x+16,y+h-1),
             (x-1,y+h-18),(x+2,y+17),(x+15,y+2)]
        self.rough_line(pts, color, 4)
        ty = y+24
        if title:
            ty = self.text(x+38, ty, title, width-76, 40, color, 'code')
            self.rough_line([(x+36,ty+3),(x+width-40,ty+6)],color,2)
            ty += 20
        for note in notes:
            text, c, kind = note if isinstance(note,tuple) else (note, 'black', 'hand')
            ty=self.text(x+38,ty,text,width-76,size,c,kind)+10
        assert ty <= y+h, (title,ty,y+h)
        rect = (x,y,x+width,y+h)
        self.boxes.append({'title': title, 'rect': rect})
        return rect

    def row(self, y, left, right):
        # specifications: title, notes, color
        a = self.card(L,y,CW,*left)
        b = self.card(R,y,CW,*right)
        return a,b,max(a[3],b[3])

    def arrow(self, pts, color='blue', label=None):
        self.rough_line(pts,color,5)
        a,b=pts[-2],pts[-1]
        ang=math.atan2(b[1]-a[1],b[0]-a[0])
        wings=[(b[0]-25*math.cos(ang-s), b[1]-25*math.sin(ang-s)) for s in (.48,-.48)]
        self.rough_line([wings[0],b,wings[1]],color,5)
        if label:
            mid=pts[len(pts)//2]
            self.text(mid[0]+18,mid[1]-26,label,600,30,color)

    @staticmethod
    def top(rect): return ((rect[0]+rect[2])/2,rect[1]-12)
    @staticmethod
    def bottom(rect): return ((rect[0]+rect[2])/2,rect[3]+12)

    def down(self,a,b,color='blue'):
        p,q=self.bottom(a),self.top(b)
        mid=(p[1]+q[1])/2
        self.arrow([p,(p[0],mid),(q[0],mid),q],color)

    def fork(self,root,targets,color='blue'):
        p=self.bottom(root)
        for t in targets:
            q=self.top(t)
            mid=min(t[1] for t in targets)-42
            self.arrow([p,(p[0],mid),(q[0],mid),q],color)

    def tag(self,y,label,color='blue'):
        self.rough_line([(L,y+28),(L+80,y+29)],color,4)
        bottom=self.text(L+105,y,label,INNER-115,38,color,'bold')
        return bottom+27

    def save(self,name,end,continuation):
        fy=end+62
        self.rough_line([(L,fy-15),(W-170,fy-10)],'grey',2)
        fy=self.text(L,fy,continuation,INNER,33,'blue')
        fy=self.text(L,fy+18,'Source inspected: 23 Sep 2026  |  Examples are illustrative  |  Application code unchanged',INNER,25,'grey','code')
        height=int(fy+60)
        for rec in self.records:
            assert rec['x'] >= 0 and rec['x']+rec['width'] <= W
            assert rec['y']+rec['height'] <= height
        final=self.im.crop((0,0,W,height))
        target=OUT/name
        final.save(target,optimize=True)
        final.resize((900,round(height*900/W))).save(OUT/(target.stem+'-preview.png'))
        return {'file':str(target),'width':W,'height':height,'text_blocks':len(self.records),'boxes':self.boxes}

def n(text,color='black',kind='hand'): return (text,color,kind)

def sheet1():
    p=Sheet(1,'userInfoServices  →  from source folders to deployable files')
    root=p.card(750,p.y,1500,'userInfoServices/',[
        'one application · one main class · one executable WAR',
        n('Java 25 | Spring Boot 4.1.1 | Gradle wrapper 9.3.0','purple')])
    y=root[3]+115
    a,b,end=p.row(y,
        ('src/main/java/com/oxygenraj/userinfo/',[
            n('UserInfoServicesApplication.java','blue','code'),
            'main() → SpringApplication.run()',
            '@SpringBootApplication → discover components',
            'SpringBootServletInitializer → WAR deployment',
            n('api/ → HTTP input, routes, output, errors','blue'),
            n('service/ → business rules + transactions','blue'),
            n('repository/ → SQL + row mapping','blue'),
            n('domain/ → internal user data','blue'),
            n('config/ → startup + security','purple'),
            'Package names organize Java; they are not URLs.'
        ],'blue'),
        ('src/main/resources/',[
            n('application.yml','blue','code'),
            'runtime settings + environment placeholders',
            n('META-INF/spring.factories','blue','code'),
            'register both early database-port processors',
            n('src/test/java/','blue','code'),
            'HTTP, security, startup and configuration tests',
            n('src/test/resources/test-schema.sql','blue','code'),
            'disposable H2 schema used by tests',
            n('H2 tests ≠ live Oracle or Eureka verification','red')
        ],'blue'))
    p.fork(root,[a,b]); y=end+75
    a,b,end=p.row(y,
        ('db/  →  schema-organized SQL',[
            n('USER_INFO_SCHEMA/','green','code'),
            n('002_create_user_details.sql','green','code'),
            'creates USER_DETAILS for application users',
            n('EUREKA_DB/003_seed_port.sql','green','code'),
            'MERGE userInfoServices port = 8770',
            n('Schemas are created externally.','red'),
            n('Folder names do not select the SQL login.','red')
        ],'green'),
        ('project root  →  build controls',[
            'build.gradle → plugins, libraries, WAR/ZIP tasks',
            'settings.gradle → Gradle project identity',
            'gradle.properties → Gradle JVM / worker limits',
            'gradlew / gradlew.bat → wrapper launchers',
            'gradle/wrapper/ → pinned Gradle version',
            'README.md + VALIDATION.md → setup / checks',
            '.gitignore → exclude generated/local files',
            '.gradle/ + .idea/ + .oca/ → tool metadata'
        ],'blue'))
    y=p.tag(end+70,'./gradlew clean build  →  two separate deliverables')
    a,b,end=p.row(y,
        ('Java + resources → bootWar',[
            'compileJava: .java → compiled .class',
            'processResources: copy application.yml',
            'classes + resources + runtime dependencies',
            n('build/libs/userInfoServices.war','green','code'),
            'embedded Tomcat → WEB-INF/lib-provided',
            n('No SQL scripts inside the WAR','red')
        ],'blue'),
        ('db/**/*.sql → dbZip',[
            'collect SQL files only',
            'preserve schema folder paths',
            n('build/libs/ → separate database ZIP','green'),
            'operator connects and executes SQL separately',
            n('No CREATE USER / schema provisioning','red'),
            n('Build never executes these SQL scripts','red')
        ],'green'))
    join=p.card(680,end+95,1640,'tests + verifyArtifacts → build result',[
        'test behaviour; verify WAR / ZIP contents',
        'fail the build if a required check fails'
    ],'purple')
    p.down(a,join);p.down(b,join,'green')
    y=join[3]+70
    a,b,end=p.row(y,
        ('dependencies → their jobs',[
            'Web MVC → HTTP routes + JSON',
            'Validation → check request fields',
            'Security → identity + access checks',
            'JDBC + Oracle driver → SQL calls',
            'Hikari → reuse user-data DB connections',
            'Eureka client → register this service',
            'Actuator → health / info',
            'H2 + JUnit → automated tests'
        ],'purple'),
        ('build/ → generated, not source',[
            'classes/ → compiled code',
            'resources/ → copied YAML and resources',
            'libs/ → WAR + database ZIP',
            'reports/tests/test/ → readable test report',
            'test-results/test/ → machine-readable results',
            'tmp/ → intermediate build files',
            n('Source YAML is copied, not regenerated.','red'),
            n('Do not edit build/. A running WAR may lock clean.','red')
        ],'blue'))
    return p.save('01-folders-and-build.png',end,'Follow the request boundary next → sheet 2: every file in api/')

def sheet2():
    p=Sheet(2,'api/  →  what comes in, which route runs, what goes out')
    root=p.card(570,p.y,1860,'UserController.java  (@RestController)',[
        'HTTP → Spring Security → route → UserService → response',
        'Spring injects UserService + AuthenticationManager',
        n('http://127.0.0.1:8770/userInfoServices/userdetails','blue','code'),
        n('api/ is a Java package; it adds NO /api URL prefix.','red')])
    a,b,end=p.row(root[3]+115,
        ('routes → operations',[
            n('POST /userdetails','blue','code'),
            'public registration → 201 + Location + user',
            n('GET /userdetails','blue','code'),
            'ADMIN only → paginated UserPage',
            n('GET /userdetails/{id}','blue','code'),
            'owner or ADMIN → one UserResponse',
            n('POST /authuserdetails','blue','code'),
            'credential check → authenticated + user'
        ],'blue'),
        ('annotations → meaning',[
            '@GetMapping / @PostMapping → route + method',
            '@RequestBody → JSON becomes a Java object',
            '@Valid → validate incoming fields',
            '@PathVariable → read {id}',
            '@RequestParam → read page / size',
            'record → compact data-holder type',
            n('Controller delegates; it does not write SQL.','purple')
        ],'purple'))
    p.fork(root,[a,b]); y=end+75
    a,b,end=p.row(y,
        ('CreateUserRequest.java',[
            'username: required; 3–64 ASCII characters',
            'first: letter or digit',
            'rest: letters, digits, dot, underscore, hyphen',
            'password: checked by @ValidPassword',
            'email: required, valid format, max 254',
            'phoneNumber: required; optional + then 7–20 digits',
            n('No incoming ID, hash, role or timestamps.','purple'),
            'toString() is redacted'
        ],'blue'),
        ('ValidPassword.java',[
            'custom annotation + validator',
            'ALL rules must pass:',
            'not null; not blank',
            'at least 10 Unicode code points',
            'at most 72 Java string characters',
            'at most 72 UTF-8 bytes (BCrypt limit)',
            n('73 ASCII characters → FAIL','red'),
            n('19 four-byte emoji → 76 bytes → FAIL','red'),
            'validation checks; it does not hash or save'
        ],'purple'))
    p.arrow([(a[2]+10,y+160),(b[0]-10,y+160)],'purple')
    y=end+75
    a,b,end=p.row(y,
        ('AuthenticateRequest.java',[
            'username + password only',
            'both must be nonblank',
            'username: max 64; password: max 72',
            'separate from registration validation',
            'toString() is redacted',
            n('Credential-check success creates no JWT or session.','red')
        ],'blue'),
        ('UserResponse.java',[
            'safe public output record',
            n('id, username, email, phoneNumber,','green','code'),
            n('role, enabled, createdAt, modifiedAt','green','code'),
            'used in create / read / list / authentication',
            n('Password and passwordHash are NEVER returned.','red')
        ],'green'))
    y=end+75
    a,b,end=p.row(y,
        ('UserPage.java',[
            'content → List<UserResponse>',
            'page → requested page, starting at 0',
            'size → requested page size',
            'totalElements → total user count',
            n('23 users, size 20:','green'),
            'page 0 → 20 users; page 1 → 3 users',
            'defaults: page 0, size 20; max size 100',
            'no totalPages / hasNext fields'
        ],'green'),
        ('ApiExceptionHandler.java',[
            '@RestControllerAdvice → shared API errors',
            '400 → invalid fields / JSON / path / query',
            '409 USER_EXISTS → duplicate',
            '404 NOT_FOUND → missing or hidden user',
            '401 INVALID_CREDENTIALS → credential failure',
            '503 DATABASE_UNAVAILABLE → DB access error',
            n('code + message + errors + timestamp','purple','code'),
            'No passwords, rejected values or raw SQL in errors.'
        ],'red'))
    y=end+75
    last=p.card(L,y,INNER,'two different error boundaries',[
        'Controller / service failure → ApiExceptionHandler for supported exceptions',
        'Security filter rejection → SecurityConfiguration writes 401 / 403',
        n('Startup failure happens before the API exists; it is not an API error response.','red')
    ],'purple')
    return p.save('02-api-files.png',last[3],'Trace creation, reading and credential checks next → sheet 3')

def sheet3():
    p=Sheet(3,'follow the data  →  controller → service → repository → Oracle')
    start=p.card(L,p.y,INNER,'POST /userInfoServices/userdetails  →  illustrative request',[
        n('username: Asha.Dev   |   email: asha@example.test   |   phoneNumber: +919876543210','blue','code'),
        'password is entered privately → CreateUserRequest + @Valid → invalid input returns 400'
    ])
    y=start[3]+95
    a,b,end=p.row(y,
        ('UserService.java → create()',[
            '@Transactional → creation is one transaction',
            'username + email: strip and lowercase',
            n('Asha.Dev → asha.dev','green'),
            'password → BCrypt hash (cost 12)',
            'phone number remains unchanged',
            n('Validation runs BEFORE normalization.','red')
        ],'blue'),
        ('UserRepository.java → create()',[
            'JdbcTemplate → parameterized INSERT',
            'SQL values bound to ? placeholders',
            'fully qualified USER_INFO_SCHEMA.USER_DETAILS',
            'database generates ID + initial timestamps',
            'role = USER; enabled = 1',
            'unique username / email conflict → 409'
        ],'green'))
    p.down(start,a);p.arrow([(a[2]+8,y+185),(b[0]-8,y+185)],'green')
    nextbox=p.card(540,end+95,1920,'database row → domain/UserAccount.java → toResponse()',[
        'SELECT saved row → internal UserAccount includes passwordHash',
        n('toResponse() removes the hash → safe UserResponse','green'),
        'controller → 201 Created + Location header + JSON user',
        n('Registration cannot give the caller ADMIN privileges.','red')
    ],'green')
    p.down(b,nextbox,'green');y=nextbox[3]+75
    a,b,end=p.row(y,
        ('GET /userdetails?page=0&size=20',[
            'Spring Security → ADMIN required',
            'UserService.list → read-only transaction',
            'repository → ORDER BY ID',
            'OFFSET = page × size; FETCH next size rows',
            'separate COUNT query → totalElements',
            'rows → UserResponse list → UserPage',
            'page 0–1,000,000; size 1–100'
        ],'blue'),
        ('GET /userdetails/{id}',[
            'positive ID; caller must authenticate',
            'UserService.get → load UserAccount',
            'owner OR ROLE_ADMIN?',
            n('YES → safe UserResponse','green'),
            n('NO → 404, deliberately hide the record','red'),
            'missing ID → 404',
            'read-only transaction; no data changed'
        ],'purple'))
    y=p.tag(end+65,'POST /authuserdetails  →  check credentials, not a login session','purple')
    a,b,end=p.row(y,
        ('AuthenticationManager → provider',[
            'AuthenticateRequest → username + password',
            'DaoAuthenticationProvider',
            'UserDetailsService → normalize username',
            'repository finds account by username',
            'verify enabled + BCrypt.matches',
            n('Match → 200: authenticated=true + user','green'),
            n('Bad credentials → 401','red')
        ],'purple'),
        ('SecurityConfiguration.java',[
            'stateless HTTP Basic',
            'protected calls need credentials every time',
            'stored USER / ADMIN → ROLE_USER / ROLE_ADMIN',
            'disabled account cannot authenticate',
            'BCrypt verifier rejects passwords >72 UTF-8 bytes',
            n('No JWT, session or persistent login is issued.','red'),
            n('Use HTTPS for remote HTTP Basic.','red')
        ],'purple'))
    p.arrow([(a[2]+10,y+180),(b[0]-10,y+180)],'purple');y=end+75
    a,b,end=p.row(y,
        ('access rules → before controller',[
            'POST registration → public',
            'POST credential check → public',
            'GET /actuator/health → public',
            'GET /actuator/info → ADMIN',
            'GET user collection → ADMIN',
            'GET one user → authenticated + ownership check',
            'other routes → denied',
            'filter errors → 401 UNAUTHORIZED / 403 FORBIDDEN'
        ],'purple'),
        ('USER_INFO_SCHEMA.USER_DETAILS',[
            'ID → generated primary key',
            'USERNAME + EMAIL → unique, required',
            'PASSWORD_HASH → hash, never plaintext',
            'PHONE_NUMBER → required',
            'USER_ROLE → USER or ADMIN; default USER',
            'ENABLED → 0 or 1; default 1',
            'CREATED_AT + MODIFIED_AT → initial timestamps',
            n('No trigger automatically refreshes MODIFIED_AT.','red')
        ],'green'))
    limit=p.card(L,end+70,INNER,'what is NOT implemented',[
        'No UI, update/delete API, password reset, JWT or configured CORS policy.',
        'Schema passwords connect Java to Oracle; application-user passwords authenticate API callers.',
        n('Eureka registration is service discovery, not user authentication.','red')
    ],'red')
    return p.save('03-request-and-security-flow.png',limit[3],'How ports and database logins are prepared before these requests → sheet 4')

def sheet4():
    p=Sheet(4,'startup wiring  →  two schema logins + shared properties + Eureka')
    root=p.card(470,p.y,2060,'java -jar userInfoServices.war',[
        'application.yml + environment → META-INF/spring.factories',
        'run early port processors BEFORE normal application beans / web server',
        n('Same database can contain both schemas; two logins do NOT mean two servers.','purple')])
    y=root[3]+115
    a,b,end=p.row(y,
        ('user-info.properties-datasource',[
            n('login: EUREKA_DB','green','code'),
            'EUREKA_DB_URL + EUREKA_DB_PASSWORD',
            'PropertiesDatabaseSettings.java validates settings',
            'password required for an enabled lookup',
            'short-lived JDBC connections for port reads',
            n('Not a second pooled Spring DataSource.','purple'),
            n('No fallback to USER_INFO_SCHEMA credentials.','red')
        ],'purple'),
        ('spring.datasource',[
            n('login: USER_INFO_SCHEMA','green','code'),
            'USER_INFO_DB_URL + USER_INFO_DB_PASSWORD',
            'Oracle JDBC driver → Hikari connection pool',
            'maximum 5; minimum idle 1',
            'JdbcTemplate → USER_INFO_SCHEMA.USER_DETAILS',
            'business user creation, queries, authentication',
            n('Schema login ≠ application-user login.','red')
        ],'green'))
    p.fork(root,[a,b]);y=end+65
    shared=p.card(L,y,INNER,'default address for BOTH connections',[
        n('jdbc:oracle:thin:@//127.0.0.1:11521/FREEPDB1','green','code'),
        'local port 11521 → existing SSH tunnel → Oracle listener → FREEPDB1',
        n('The Java application does not start or maintain the tunnel.','red')
    ],'green');y=shared[3]+75
    a,b,end=p.row(y,
        ('DatabasePortEnvironmentPostProcessor.java',[
            n('USER_INFO_DB_PORT_ENABLED=false (default)','purple','code'),
            'FALSE → normal server.port configuration',
            'USER_INFO_PORT default → 8770',
            'TRUE → OraclePortReader.java',
            'read EUREKA_DB.PROPERTIES',
            n('APPLICATION=userInfoServices','green','code'),
            n('PROFILE=jdbc, LABEL=jdbc, KEY=server.port','green','code'),
            'database value overrides server.port'
        ],'purple'),
        ('EurekaDatabasePortEnvironmentPostProcessor.java',[
            'run after the own-port processor',
            n('EUREKA_CLIENT_ENABLED=true','purple','code'),
            n('USER_INFO_EUREKA_DB_PORT_ENABLED=true','purple','code'),
            'both enabled → OracleEurekaPortReader.java',
            'read the SAME EUREKA_DB.PROPERTIES table',
            n('APPLICATION=eureka-server','green','code'),
            n('PROFILE=jdbc, LABEL=jdbc, KEY=server.port','green','code'),
            'replace ONLY the port in EUREKA_URL'
        ],'purple'))
    y=end+75
    a,b,end=p.row(y,
        ('OraclePortReader.java + OracleEurekaPortReader.java',[
            'connect as EUREKA_DB; read only, no writes',
            'verify session/current schema + FREEPDB1',
            'require exactly one matching row',
            'require integer port from 1 to 65535',
            'bounded connection / read / query timeouts',
            n('Enabled lookup failure → startup fails.','red'),
            n('Read once at startup; restart after port changes.','red')
        ],'green'),
        ('DatabaseReadiness.java + SecurityConfiguration.java',[
            'readiness uses pooled USER_INFO_SCHEMA access',
            'verify schema identity + FREEPDB1',
            'verify expected USER_DETAILS columns',
            'security creates BCrypt + HTTP access rules',
            'start API → default 127.0.0.1:8770/userInfoServices',
            'Eureka client → register over HTTP',
            n('DB lookup success does not prove Eureka registration.','red')
        ],'blue'))
    y=end+75
    a,b,end=p.row(y,
        ('separate SQL ZIP → manual deployment',[
            'connect as USER_INFO_SCHEMA:',
            n('002_create_user_details.sql','green','code'),
            '→ create USER_DETAILS table',
            'connect as EUREKA_DB:',
            n('003_seed_port.sql','green','code'),
            '→ MERGE userInfoServices port = 8770',
            n('EUREKA_DB.PROPERTIES must already exist.','red'),
            'schema creation is external'
        ],'green'),
        ('switches + boundaries to remember',[
            'Eureka DB lookup disabled → use configured URL',
            'Eureka client disabled → no registration or Eureka port lookup',
            'own-port lookup remains an independent switch',
            n('EUREKA_DB_PORT_ENABLED is NOT used here.','red','code'),
            'spring.sql.init.mode=never → no auto SQL execution',
            'No USER_INFO_SCHEMA.PROPERTIES needed',
            'No cross-schema SELECT grant needed when using EUREKA_DB login',
            'External Tomcat owns its connector port'
        ],'red'))
    return p.save('04-startup-databases-and-ports.png',end,'Build prepares files → startup resolves settings → API requests use the running application.')

if __name__=='__main__':
    results=[sheet1(),sheet2(),sheet3(),sheet4()]
    (OUT/'render-validation.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    with zipfile.ZipFile(OUT/'userinfoservices-scratchpad-images.zip','w',zipfile.ZIP_DEFLATED) as z:
        for result in results:
            path=Path(result['file'])
            z.write(path,path.name)
    overlap_checks=0
    for result in results:
        with Image.open(result['file']) as rendered:
            assert rendered.size == (result['width'], result['height'])
            rendered.verify()
        for i, a in enumerate(result['boxes']):
            for b in result['boxes'][i+1:]:
                x,y=a['rect'],b['rect']
                assert (min(x[2],y[2]) <= max(x[0],y[0]) or
                        min(x[3],y[3]) <= max(x[1],y[1])), (a['title'],b['title'])
                overlap_checks += 1
    with zipfile.ZipFile(OUT/'userinfoservices-scratchpad-images.zip') as z:
        assert z.namelist() == [Path(row['file']).name for row in results]
        assert z.testzip() is None
    print(f'PASS: four PNGs; {overlap_checks} box-overlap checks; four-image ZIP integrity.')
    for result in results:
        print(result['file'],result['width'],result['height'],result['text_blocks'])
